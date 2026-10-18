import { createReadStream, createWriteStream } from "node:fs";
import {
  type FileHandle,
  chmod,
  lstat,
  mkdir,
  open,
  readdir,
  readlink,
  rename,
  rm,
  stat,
  symlink,
  utimes,
  writeFile,
} from "node:fs/promises";
import { basename, dirname, join } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { createGunzip, createGzip } from "node:zlib";
import { withConfigLock } from "../config-lock.js";
import { backupsDir, HostError, type HostEnv, installedPiVersion } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { runningPi, waitForPiExit } from "../pi-processes.js";
import { readTar, tarStream, TarError, type TarEntry } from "../tar.js";

const FORMAT = "pipkin-config-backup";
const VERSION = 1;
const MANIFEST = "manifest.json";
const MAX_MANIFEST = 64 * 1024;
/** Entries under `config/` in the archive; the manifest sits beside them. */
const CONFIG = "config";
const PROGRESS_INTERVAL_MS = 300;

/**
 * Top-level entries of the config folder that are not configuration: chat history, Pi's downloaded fd and rg, scratch
 * space and logs. A backup leaves them out, and a restore leaves them where they are.
 */
const NOT_CONFIGURATION: Record<string, true> = {
  sessions: true,
  bin: true,
  tmp: true,
  "pi-debug.log": true,
  "crashes.json": true,
};

interface Manifest {
  format: string;
  version: number;
  createdAt: string;
  piVersion: string | null;
}

/** A backup's file name as the app names it: a plain `.tar.gz` name in the backups folder. */
function backupPath(env: HostEnv, name: string): string {
  if (basename(name) !== name || name.startsWith(".") || !name.endsWith(".tar.gz") || name.includes("\0")) {
    throw new HostError("bad_name", `Not a backup name: ${name}`);
  }
  return join(backupsDir(env), name);
}

/**
 * Sends a progress line at most every `PROGRESS_INTERVAL_MS`: `<verb> <count> items`, which the app shows through the
 * plural string [key] names.
 */
function progress(ctx: ModeContext, verb: string, key: string): (count: number) => void {
  let last = 0;
  return (count) => {
    if (Date.now() - last < PROGRESS_INTERVAL_MS) return;
    last = Date.now();
    const line = `${verb} ${count} ${count === 1 ? "item" : "items"}`;
    ctx.conn.send({ type: "pipkin_progress", line, lineI18n: { key, args: { count } } });
  };
}

/** The failure of a file whose manifest doesn't make it a Pipkin backup. */
function notBackup(): HostError {
  return new HostError("not_backup", "This file is not a Pipkin configuration backup", { key: "backup_not_pipkin" });
}

/** Reads the first `size` bytes of an open file, then closes it. */
async function* fileContent(handle: FileHandle, size: number): AsyncGenerator<Buffer> {
  try {
    if (size > 0) yield* handle.createReadStream({ start: 0, end: size - 1, autoClose: false }) as AsyncIterable<Buffer>;
  } finally {
    await handle.close();
  }
}

/** Opens `path` for reading, or null when it no longer exists. */
async function openIfPresent(path: string): Promise<FileHandle | null> {
  try {
    return await open(path, "r");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  }
}

/**
 * The config folder as tar entries under `config/`, folders before their contents. A file that disappears while the
 * backup runs is left out; one that changes size is cut or zero-padded to the size it had when listed.
 */
async function* configEntries(configDir: string, counted: (count: number) => void): AsyncGenerator<TarEntry> {
  let count = 0;
  async function* walk(dir: string, relativeDir: string): AsyncGenerator<TarEntry> {
    let names: string[];
    try {
      names = (await readdir(dir)).toSorted();
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
      throw error;
    }
    for (const name of names) {
      if (relativeDir === "" && NOT_CONFIGURATION[name] === true) continue;
      const path = join(dir, name);
      const relativePath = relativeDir === "" ? name : `${relativeDir}/${name}`;
      let stats;
      try {
        stats = await lstat(path);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
        throw error;
      }
      const entryPath = `${CONFIG}/${relativePath}`;
      const mtime = Math.floor(stats.mtimeMs / 1000);
      if (stats.isDirectory()) {
        yield { type: "directory", path: entryPath, mode: stats.mode, mtime };
        counted(++count);
        yield* walk(path, relativePath);
      } else if (stats.isFile()) {
        // Opened before its header is written, so a file deleted since it was listed is skipped, not a failed backup.
        const handle = await openIfPresent(path);
        if (handle === null) continue;
        yield { type: "file", path: entryPath, mode: stats.mode, mtime, size: stats.size, content: fileContent(handle, stats.size) };
        counted(++count);
      } else if (stats.isSymbolicLink()) {
        yield { type: "symlink", path: entryPath, target: await readlink(path), mtime };
        counted(++count);
      }
    }
  }
  yield* walk(configDir, "");
}

/** A fresh name, `pipkin-config-20261015T143000Z.tar.gz`, with a counter when one already has that second. */
async function newBackupName(dir: string): Promise<string> {
  const stamp = new Date().toISOString().replaceAll(/[-:]/g, "").replace(/\.\d+Z$/, "Z");
  const taken = new Set(await readdir(dir));
  for (let counter = 1; ; counter++) {
    const name = counter === 1 ? `pipkin-config-${stamp}.tar.gz` : `pipkin-config-${stamp}-${counter}.tar.gz`;
    if (!taken.has(name)) return name;
  }
}

/** Reads only the manifest at the front of a backup. */
async function readManifest(path: string): Promise<Manifest> {
  const file = createReadStream(path);
  const gunzip = createGunzip();
  const done = pipeline(file, gunzip).catch(() => undefined);
  try {
    for await (const entry of readTar(gunzip as AsyncIterable<Buffer>)) {
      return await parseManifest(entry);
    }
    throw new TarError("The archive is empty", { key: "archive_empty" });
  } finally {
    file.destroy();
    gunzip.destroy();
    await done;
  }
}

async function parseManifest(entry: TarEntry): Promise<Manifest> {
  if (entry.type !== "file" || entry.path !== MANIFEST || entry.size > MAX_MANIFEST) throw notBackup();
  const chunks: Buffer[] = [];
  for await (const chunk of entry.content) chunks.push(chunk);
  let manifest: unknown;
  try {
    manifest = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw notBackup();
  }
  if (manifest === null || typeof manifest !== "object" || !("format" in manifest) || manifest.format !== FORMAT) throw notBackup();
  if (!("version" in manifest) || manifest.version !== VERSION) {
    throw new HostError("backup_version", "This backup is from a newer Pipkin; update Pipkin to restore it", {
      key: "backup_from_newer_pipkin",
    });
  }
  const createdAt = "createdAt" in manifest && typeof manifest.createdAt === "string" ? manifest.createdAt : "";
  const piVersion = "piVersion" in manifest && typeof manifest.piVersion === "string" ? manifest.piVersion : null;
  return { format: FORMAT, version: VERSION, createdAt, piVersion };
}

async function backupInfo(env: HostEnv, name: string): Promise<JsonObject> {
  const path = backupPath(env, name);
  const stats = await stat(path);
  const info: JsonObject = { name, size: stats.size, createdAt: stats.mtime.toISOString(), piVersion: null, error: null };
  try {
    const manifest = await readManifest(path);
    if (!Number.isNaN(Date.parse(manifest.createdAt))) info.createdAt = manifest.createdAt;
    info.piVersion = manifest.piVersion;
  } catch (error) {
    if (error instanceof HostError || error instanceof TarError) {
      info.error = error.message;
      info.errorI18n = error.i18n;
    } else {
      info.error = "This file can't be read as a backup";
      info.errorI18n = { key: "backup_unreadable" };
    }
  }
  return info;
}

/** Lists the backups folder, newest first. A file that isn't a readable backup is listed with an `error`. */
export async function listBackups(ctx: ModeContext): Promise<JsonObject> {
  const dir = backupsDir(ctx.env);
  const names = await readdir(dir).catch(() => [] as string[]);
  const backups: JsonObject[] = [];
  for (const name of names) {
    if (name.startsWith(".") || !name.endsWith(".tar.gz")) continue;
    if (!(await stat(join(dir, name)).then((stats) => stats.isFile(), () => false))) continue;
    backups.push(await backupInfo(ctx.env, name));
  }
  backups.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)) || String(b.name).localeCompare(String(a.name)));
  return { dir, backups };
}

/** Writes a backup of the configuration (the config folder without sessions, caches and logs) into the backups folder. */
export async function createBackup(ctx: ModeContext): Promise<JsonObject> {
  return withConfigLock(ctx.env, async () => {
    await settleRestores(ctx.env);
    const dir = backupsDir(ctx.env);
    await mkdir(dir, { recursive: true });
    await removePartialBackups(dir);
    const name = await newBackupName(dir);
    const partial = join(dir, `.${name}.partial-${process.pid}`);
    const manifest = JSON.stringify({
      format: FORMAT,
      version: VERSION,
      createdAt: new Date().toISOString(),
      piVersion: await installedPiVersion(ctx.env.root),
    } satisfies Manifest);
    const counted = progress(ctx, "Backed up", "backup_progress_backed_up");
    async function* entries(): AsyncGenerator<TarEntry> {
      const bytes = Buffer.from(manifest);
      yield { type: "file", path: MANIFEST, mode: 0o644, mtime: Math.floor(Date.now() / 1000), size: bytes.length, content: Readable.from([bytes]) };
      yield* configEntries(ctx.env.configDir, counted);
    }
    try {
      await pipeline(Readable.from(tarStream(entries())), createGzip(), createWriteStream(partial, { flush: true }), { signal: ctx.signal });
      await rename(partial, join(dir, name));
    } catch (error) {
      await rm(partial, { force: true });
      throw error;
    }
    return { backup: await backupInfo(ctx.env, name) };
  });
}

/** Deletes a backup; one that is already gone counts as deleted. */
export async function deleteBackup(ctx: ModeContext): Promise<JsonObject> {
  const path = backupPath(ctx.env, stringParam(ctx.params, "name"));
  return withConfigLock(ctx.env, async () => {
    await rm(path, { force: true });
    return {};
  });
}

async function removePartialBackups(dir: string): Promise<void> {
  for (const name of await readdir(dir)) {
    if (name.startsWith(".") && name.includes(".partial-")) await rm(join(dir, name), { force: true });
  }
}

/** Checks one archive path under `config/` and returns it relative to the config folder. */
function configPath(path: string): string {
  if (!path.startsWith(`${CONFIG}/`)) {
    throw new HostError("bad_backup", `The backup holds a file outside the configuration: ${path}`, {
      key: "backup_path_outside_config",
      args: { path },
    });
  }
  const relativePath = path.slice(CONFIG.length + 1);
  const segments = relativePath.split("/");
  if (segments.some((segment) => segment === "" || segment === "." || segment === ".." || segment.includes("\0"))) {
    throw new HostError("bad_backup", `The backup holds an unsafe path: ${path}`, { key: "backup_path_unsafe", args: { path } });
  }
  const name = segments[0] as string;
  if (NOT_CONFIGURATION[name] === true) {
    throw new HostError("bad_backup", `The backup holds ${name}, which a restore never replaces`, {
      key: "backup_holds_excluded",
      args: { name },
    });
  }
  return relativePath;
}

/**
 * Unpacks a backup into `staging`, checking it as it goes: the manifest first, every path inside `config/` and outside
 * what a restore leaves alone, no path twice, nothing beneath a symlink. Symlinks are made last and folder permissions
 * set last, so nothing is ever written through a link or into a folder the backup made read-only.
 */
async function extract(ctx: ModeContext, archive: string, staging: string): Promise<void> {
  const file = createReadStream(archive);
  const gunzip = createGunzip();
  const done = pipeline(file, gunzip);
  done.catch(() => undefined);
  const seen = new Set<string>();
  const links: { path: string; target: string }[] = [];
  const linkPaths = new Set<string>();
  const folders: { path: string; mode: number; mtime: number }[] = [];
  const counted = progress(ctx, "Restored", "backup_progress_restored");
  let first = true;
  try {
    for await (const entry of readTar(gunzip as AsyncIterable<Buffer>)) {
      if (ctx.signal.aborted) throw new HostError("aborted", "The restore was cancelled");
      if (first) {
        await parseManifest(entry);
        first = false;
        continue;
      }
      if (entry.path === CONFIG && entry.type === "directory") continue;
      const relativePath = configPath(entry.path);
      if (seen.has(relativePath)) {
        throw new HostError("bad_backup", `The backup holds ${relativePath} twice`, { key: "backup_path_twice", args: { path: relativePath } });
      }
      for (let index = relativePath.indexOf("/"); index >= 0; index = relativePath.indexOf("/", index + 1)) {
        if (linkPaths.has(relativePath.slice(0, index))) {
          throw new HostError("bad_backup", `The backup holds ${relativePath} beneath a symlink`, {
            key: "backup_path_beneath_symlink",
            args: { path: relativePath },
          });
        }
      }
      seen.add(relativePath);
      const target = join(staging, relativePath);
      if (entry.type === "directory") {
        await mkdir(target, { recursive: true });
        folders.push({ path: target, mode: entry.mode & 0o7777, mtime: entry.mtime });
      } else if (entry.type === "symlink") {
        // Anything unpacked beneath this path made it a folder; a symlink beneath it is not on disk yet.
        if ((await exists(target)) || links.some((link) => link.path.startsWith(`${relativePath}/`))) {
          throw new HostError("bad_backup", `The backup holds files beneath the symlink ${relativePath}`, {
            key: "backup_files_beneath_symlink",
            args: { path: relativePath },
          });
        }
        links.push({ path: relativePath, target: entry.target });
        linkPaths.add(relativePath);
      } else {
        await mkdir(dirname(target), { recursive: true });
        const handle = await open(target, "wx", 0o600);
        try {
          for await (const chunk of entry.content) await handle.write(chunk);
        } finally {
          await handle.close();
        }
        await chmod(target, entry.mode & 0o7777);
        await utimes(target, entry.mtime, entry.mtime);
      }
      counted(seen.size);
    }
    if (first) throw notBackup();
    await done;
  } catch (error) {
    file.destroy();
    gunzip.destroy();
    if (error instanceof TarError) {
      throw new HostError("bad_backup", `The backup is damaged: ${error.message}`, {
        key: "backup_damaged",
        args: { reason: error.message, reasonI18n: error.i18n },
      });
    }
    const zlibCode = (error as NodeJS.ErrnoException).code;
    if (zlibCode === "Z_DATA_ERROR" || zlibCode === "Z_BUF_ERROR") {
      const reason = (error as Error).message;
      throw new HostError("bad_backup", `The backup is damaged: ${reason}`, { key: "backup_damaged", args: { reason } });
    }
    throw error;
  }
  for (const link of links) {
    const path = join(staging, link.path);
    await mkdir(dirname(path), { recursive: true });
    await symlink(link.target, path);
  }
  for (const folder of folders.toReversed()) {
    await chmod(folder.path, folder.mode);
    await utimes(folder.path, folder.mtime, folder.mtime);
  }
}

async function exists(path: string): Promise<boolean> {
  return lstat(path).then(
    () => true,
    () => false,
  );
}

/**
 * Puts an unpacked backup in place of the configuration: first every configuration entry moves aside into `old`, then
 * every entry of `staging` moves in. Sessions and the rest of `NOT_CONFIGURATION` never move. Each step is a rename,
 * and a marker records the end of the first step, so a swap cut short by a crash is finished by the next one.
 */
async function swap(configDir: string, staging: string, old: string): Promise<void> {
  await mkdir(configDir, { recursive: true });
  if (!(await exists(`${old}.moved`))) {
    await mkdir(old, { recursive: true });
    for (const name of await readdir(configDir)) {
      if (NOT_CONFIGURATION[name] !== true) await rename(join(configDir, name), join(old, name));
    }
    await writeFile(`${old}.moved`, "");
  }
  for (const name of await readdir(staging)) await rename(join(staging, name), join(configDir, name));
  await rm(old, { recursive: true, force: true });
  await rm(staging, { recursive: true, force: true });
  await rm(`${staging}.complete`, { force: true });
  await rm(`${old}.moved`, { force: true });
}

/**
 * Finishes a restore a crash interrupted after its backup was fully unpacked, and deletes one interrupted before.
 * Either way only configuration is involved: sessions never leave the config folder. A Pi may have started since the
 * crash released the lock, and the swap must not happen under it: with `signal` (a restore, which stops every chat
 * first) it waits for Pi to exit; without (a backup, while chats run) it leaves the swap for later while one runs.
 */
async function settleRestores(env: HostEnv, signal?: AbortSignal): Promise<void> {
  const parent = dirname(env.configDir);
  const prefix = `${basename(env.configDir)}.restore-`;
  const ids = new Set<string>();
  for (const name of await readdir(parent).catch(() => [] as string[])) {
    if (name.startsWith(prefix)) ids.add(name.slice(prefix.length).replace(/\.complete$/, ""));
  }
  for (const id of ids) {
    const staging = `${env.configDir}.restore-${id}`;
    const old = `${env.configDir}.old-${id}`;
    if ((await exists(staging)) && (await exists(`${staging}.complete`))) {
      if (signal !== undefined) await waitForPiExit(env.configDir, signal);
      else if ((await runningPi(env.configDir)).length > 0) continue;
      await swap(env.configDir, staging, old);
      continue;
    }
    // Unpacking never finished, so the configuration was never touched; or the swap finished and only markers remain.
    await rm(staging, { recursive: true, force: true });
    await rm(`${staging}.complete`, { force: true });
    await rm(old, { recursive: true, force: true });
    await rm(`${old}.moved`, { force: true });
  }
}

/**
 * Replaces the configuration with a backup's: everything in the config folder except sessions, caches and logs. The
 * backup is unpacked and checked beside the config folder first, so a damaged one changes nothing; then, once no Pi
 * uses the config folder, the swap is a handful of renames.
 */
export async function restoreBackup(ctx: ModeContext): Promise<JsonObject> {
  const archive = backupPath(ctx.env, stringParam(ctx.params, "name"));
  return withConfigLock(ctx.env, async () => {
    await settleRestores(ctx.env, ctx.signal);
    if (!(await exists(archive))) throw new HostError("not_found", "This backup no longer exists", { key: "backup_missing" });
    const id = `${Date.now()}-${process.pid}`;
    const staging = `${ctx.env.configDir}.restore-${id}`;
    await mkdir(dirname(ctx.env.configDir), { recursive: true });
    await mkdir(staging);
    try {
      await extract(ctx, archive, staging);
      await writeFile(`${staging}.complete`, "");
    } catch (error) {
      await rm(staging, { recursive: true, force: true });
      throw error;
    }
    try {
      await waitForPiExit(ctx.env.configDir, ctx.signal);
    } catch (error) {
      await rm(staging, { recursive: true, force: true });
      await rm(`${staging}.complete`, { force: true });
      throw error;
    }
    await swap(ctx.env.configDir, staging, `${ctx.env.configDir}.old-${id}`);
    return {};
  });
}
