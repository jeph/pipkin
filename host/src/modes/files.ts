import type { Dirent, Stats } from "node:fs";
import { mkdir, open, readdir, realpath, stat } from "node:fs/promises";
import { extname, isAbsolute, join, posix, relative, resolve } from "node:path";
import { folderNotFound, type HostEnv, HostError, type HostText } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { listingScript, parseListing } from "../remote/fs.js";
import { quote } from "../remote/script.js";
import { READ_IDLE_MS, runScript } from "../remote/ssh.js";
import { asRemoteError, connectionCode, remoteFolder, type RemoteFolder } from "./remote-folder.js";

/** Escaping can double a text file's size in JSON; 2 MiB stays well under the app's 16 MiB line limit. */
const TEXT_LIMIT = 2 * 1024 * 1024;
/** Base64 inflates by a third, so 8 MiB of image is under 11 MiB on the line. */
const IMAGE_LIMIT = 8 * 1024 * 1024;
/** Enough of an oversized file to tell binary from text, so the app can say which it is. */
const SNIFF_BYTES = 8 * 1024;
/** Paseo's threshold: text has few control characters besides tab, LF and CR. */
const CONTROL_RATIO = 0.3;
const IMAGE_EXTENSIONS: Record<string, true> = { ".png": true, ".jpg": true, ".jpeg": true, ".gif": true, ".webp": true, ".bmp": true };

/** What the app shows for Node's error codes, with the key of its string resource. */
const MESSAGES: Record<string, { error: string; key: string }> = {
  EACCES: { error: "Termux can't open this.", key: "files_no_access" },
  EPERM: { error: "Termux can't open this.", key: "files_no_access" },
  ENOENT: { error: "This file or folder no longer exists.", key: "files_gone" },
  ENOTDIR: { error: "This isn't a folder.", key: "not_a_folder" },
  EISDIR: { error: "This is a folder.", key: "files_is_folder" },
  ELOOP: { error: "This link goes round in circles.", key: "files_link_loop" },
  EROFS: { error: "This storage is read-only.", key: "files_read_only" },
  ENOSPC: { error: "The device is full.", key: "files_device_full" },
};
/** Entries that vanished, or links that dangle or loop, between readdir and stat. */
const MISSING: Record<string, true> = { ENOENT: true, ENOTDIR: true, ELOOP: true };

function errorCode(error: unknown): string | undefined {
  return error instanceof Error && "code" in error && typeof error.code === "string" ? error.code : undefined;
}

/**
 * A reply's `error` and `errorI18n` for [error]: a [HostError]'s message is already fit to show. A remote workspace's
 * failed connection also gives its code as `errorCode`.
 */
function describe(error: unknown): { error: string; errorI18n: HostText | undefined; errorCode?: string } {
  if (error instanceof HostError) return { error: error.message, errorI18n: error.i18n, ...connectionCode(error) };
  const code = errorCode(error);
  if (code === undefined) return { error: "Can't read this (unknown error).", errorI18n: { key: "files_unreadable_unknown" } };
  const known = MESSAGES[code];
  if (known !== undefined) return { error: known.error, errorI18n: { key: known.key } };
  return { error: `Can't read this (${code}).`, errorI18n: { key: "files_unreadable", args: { code } } };
}

function inside(root: string, path: string): boolean {
  const rel = relative(root, path);
  return rel !== ".." && !rel.startsWith("../") && !isAbsolute(rel);
}

/** The workspace-relative spelling the app uses: `/`-separated, `.` for the root itself. */
function relativeTo(root: string, path: string): string {
  return relative(root, path) || ".";
}

interface Scoped {
  realRoot: string;
  /** The path as the app named it, under the workspace root. */
  requested: string;
  /** [requested] with links resolved; inside the real root. */
  real: string;
}

function outsideWorkspace(): HostError {
  return new HostError("outside_workspace", "That path is outside the workspace.", { key: "files_outside_workspace" });
}

/** Resolves [raw] under [root], refusing anything that leaves it by `..` or through a link. */
async function scope(root: string, raw: string): Promise<Scoped> {
  let realRoot: string;
  try {
    if (!isAbsolute(root)) throw new Error("Not absolute");
    realRoot = await realpath(root);
    if (!(await stat(realRoot)).isDirectory()) throw new Error("Not a folder");
  } catch {
    throw folderNotFound("not_found", root);
  }
  const requested = resolve(root, raw);
  const real = inside(root, requested) ? await realpath(requested) : undefined;
  if (real === undefined || !inside(realRoot, real)) throw outsideWorkspace();
  return { realRoot, requested, real };
}

/** Stats [dirent], following links; undefined for dangling links and links out of the workspace. */
async function entryStats(dir: Scoped, dirent: Dirent): Promise<Stats | undefined> {
  try {
    let target = join(dir.real, dirent.name);
    if (dirent.isSymbolicLink()) {
      target = await realpath(target);
      if (!inside(dir.realRoot, target)) return undefined;
    }
    return await stat(target);
  } catch (error) {
    if (MISSING[errorCode(error) ?? ""] === true) return undefined;
    throw error;
  }
}

interface Entry {
  name: string;
  path: string;
  kind: "directory" | "file";
  size: number;
  modified: number;
}

/** [dir]'s entries, without dangling links and links out of the workspace. */
async function localEntries(dir: Scoped, path: string): Promise<Entry[]> {
  const dirents = await readdir(dir.real, { withFileTypes: true });
  const found = await Promise.all(
    dirents.map(async (dirent): Promise<Entry | undefined> => {
      const stats = await entryStats(dir, dirent);
      if (stats === undefined) return undefined;
      return {
        name: dirent.name,
        path: childPath(path, dirent.name),
        kind: stats.isDirectory() ? "directory" : "file",
        size: stats.size,
        modified: Math.floor(stats.mtimeMs),
      };
    }),
  );
  return found.filter((entry) => entry !== undefined);
}

function childPath(path: string, name: string): string {
  return path === "." ? name : `${path}/${name}`;
}

async function list(env: HostEnv, root: string, raw: string): Promise<JsonObject> {
  let path = raw;
  try {
    const folder = await remoteFolder(env, root);
    let found: Entry[];
    if (folder === null) {
      const dir = await scope(root, raw);
      path = relativeTo(root, dir.requested);
      found = await localEntries(dir, path);
    } else {
      const requested = remoteRequested(folder.path, raw);
      path = relativeTo(folder.path, requested);
      found = await remoteEntries(folder, requested, path);
    }
    const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });
    const entries = found.toSorted((a, b) => Number(b.kind === "directory") - Number(a.kind === "directory") || collator.compare(a.name, b.name));
    return { path, entries };
  } catch (error) {
    return { path, entries: [], ...describe(error) };
  }
}

/** Up to [length] bytes from the start of [path]. */
async function readStart(path: string, length: number): Promise<Buffer> {
  const handle = await open(path, "r");
  try {
    const buffer = Buffer.alloc(length);
    let filled = 0;
    while (filled < length) {
      const { bytesRead } = await handle.read(buffer, filled, length - filled, filled);
      if (bytesRead === 0) break;
      filled += bytesRead;
    }
    return buffer.subarray(0, filled);
  } finally {
    await handle.close();
  }
}

/**
 * [bytes] as text, or undefined when they look binary (Paseo's test: a NUL, invalid UTF-8, or too many
 * control characters). A [partial] read may end mid-character, which isn't held against it.
 */
function asText(bytes: Buffer, partial: boolean): string | undefined {
  let control = 0;
  for (const byte of bytes) {
    if (byte === 0) return undefined;
    if ((byte < 32 && byte !== 9 && byte !== 10 && byte !== 13) || byte === 127) control += 1;
  }
  if (bytes.length > 0 && control / bytes.length > CONTROL_RATIO) return undefined;
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes, { stream: partial });
  } catch {
    return undefined;
  }
}

/**
 * How much of a file of [size] bytes its reply needs: all of an image or text file within its limit, none of a larger
 * image, and the first [SNIFF_BYTES] of a larger file, to tell binary from text.
 */
function bytesNeeded(size: number, image: boolean): number {
  if (image) return size > IMAGE_LIMIT ? 0 : size;
  return size > TEXT_LIMIT ? SNIFF_BYTES : size;
}

/** The reply for file [path] of [size] bytes, from the [bytes] of it [bytesNeeded] asked for. */
function fileReply(path: string, size: number, image: boolean, bytes: Buffer): JsonObject {
  if (image) {
    if (size > IMAGE_LIMIT) return { path, size, kind: "too_large" };
    return { path, size: bytes.length, kind: "image", content: bytes.toString("base64") };
  }
  if (size > TEXT_LIMIT) return { path, size, kind: asText(bytes, true) === undefined ? "binary" : "too_large" };
  const content = asText(bytes, false);
  return content === undefined ? { path, size: bytes.length, kind: "binary" } : { path, size: bytes.length, kind: "text", content };
}

function isImage(path: string): boolean {
  return IMAGE_EXTENSIONS[extname(path).toLowerCase()] === true;
}

async function read(env: HostEnv, root: string, raw: string): Promise<JsonObject> {
  let path = raw;
  try {
    const folder = await remoteFolder(env, root);
    if (folder !== null) {
      const requested = remoteRequested(folder.path, raw);
      path = relativeTo(folder.path, requested);
      const image = isImage(requested);
      const { size, bytes } = await remoteRead(folder, requested, image);
      return fileReply(path, size, image, bytes);
    }
    const file = await scope(root, raw);
    path = relativeTo(root, file.requested);
    const stats = await stat(file.real);
    if (stats.isDirectory()) throw isFolder();
    // Opening a FIFO or device would block or never end.
    if (!stats.isFile()) throw notRegular();
    const image = isImage(file.requested);
    const needed = bytesNeeded(stats.size, image);
    return fileReply(path, stats.size, image, needed === 0 ? Buffer.alloc(0) : await readStart(file.real, needed));
  } catch (error) {
    return { path, ...describe(error) };
  }
}

function isFolder(): HostError {
  return new HostError("is_folder", "This is a folder.", { key: "files_is_folder" });
}

function notRegular(): HostError {
  return new HostError("not_file", "This isn't a regular file.", { key: "files_not_regular" });
}

function nameTaken(name: string): HostError {
  return new HostError("name_taken", `A file or folder named ${name} already exists.`, { key: "files_name_taken", args: { name } });
}

async function makeFolder(env: HostEnv, root: string, raw: string, rawName: string): Promise<JsonObject> {
  try {
    const name = rawName.trim();
    if (name === "" || name === "." || name === ".." || name.includes("/") || name.includes("\0")) {
      throw new HostError("bad_name", "Folder names can't be empty, contain / or be . or ..", { key: "files_folder_name_invalid" });
    }
    const folder = await remoteFolder(env, root);
    if (folder !== null) {
      const parent = remoteRequested(folder.path, raw);
      await remoteMakeFolder(folder, parent, name);
      return { path: relativeTo(folder.path, posix.join(parent, name)) };
    }
    const parent = await scope(root, raw);
    try {
      await mkdir(join(parent.real, name));
    } catch (error) {
      if (errorCode(error) !== "EEXIST") throw error;
      throw nameTaken(name);
    }
    return { path: relativeTo(root, join(parent.requested, name)) };
  } catch (error) {
    return describe(error);
  }
}

// A remote workspace: the same requests on the remote folder its stand-in names, each in one `ssh` round trip.

/** What the remote scripts' exit statuses mean, besides the errno ones `remote/fs.ts` uses. */
const REMOTE_OUTSIDE = 3;
const REMOTE_ROOT_MISSING = 4;
const REMOTE_NOT_REGULAR = 5;
const REMOTE_NAME_TAKEN = 6;
const REMOTE_ERRNO: Record<number, string> = { 2: "ENOENT", 13: "EACCES", 20: "ENOTDIR", 21: "EISDIR" };

/** A remote script's failed exit [status]: the errno it stands for as [code], `EIO` for any other. */
class RemoteExit extends Error {
  readonly code: string;

  constructor(
    readonly status: number | null,
    stderr: string,
  ) {
    const code = (status === null ? undefined : REMOTE_ERRNO[status]) ?? "EIO";
    super(stderr === "" ? code : `${code}: ${stderr}`);
    this.code = code;
  }
}

/** [raw] under remote folder [root], refusing a `..` out of it; links are for [scopeScript] to check there. */
function remoteRequested(root: string, raw: string): string {
  const requested = posix.resolve(root, raw);
  if (!inside(root, requested)) throw outsideWorkspace();
  return requested;
}

/** Cuts variable [name], captured with an `x` after it, back to the exact path `$(…)` printed. */
function exact(name: string): string {
  return `${name}=\${${name}%x}; ${name}=\${${name}%$'\\n'}`;
}

/**
 * The start of each remote script: `R` is workspace folder [root] with links resolved, and `T` is [target], which must
 * resolve inside it, so no link leads out of the workspace. Each resolved path is captured with an `x` after it and
 * then cut back to the exact name, as `$(…)` drops trailing newlines, which a folder name may end with: `/ws<LF>` would
 * otherwise pass for the workspace `/ws`.
 */
function scopeScript(root: string, target: string): string[] {
  return [
    `r=${quote(root)}`,
    `t=${quote(target)}`,
    `R=$(cd -P -- "$r" 2>/dev/null && pwd -P && echo x) || exit ${String(REMOTE_ROOT_MISSING)}`,
    exact("R"),
    `[ -e "$t" ] || exit 2`,
    `if [ -d "$t" ]; then T=$(cd -P -- "$t" 2>/dev/null && pwd -P && echo x) || exit 13`,
    `else T=$({ realpath -- "$t" 2>/dev/null || readlink -f -- "$t" 2>/dev/null; } && echo x) || exit 2; fi`,
    exact("T"),
    `case $R in /) ;; *) case $T in "$R" | "$R"/*) ;; *) exit ${String(REMOTE_OUTSIDE)} ;; esac ;; esac`,
  ];
}

/** Runs [lines] on [folder]'s host; a failed exit becomes the error the same failure is locally. */
async function runRemote(folder: RemoteFolder, lines: string[]): Promise<Buffer> {
  let result;
  try {
    result = await runScript(folder.remote, lines.join("\n"), { idleTimeoutMs: READ_IDLE_MS });
  } catch (error) {
    throw asRemoteError(folder.remote, error);
  }
  if (result.code === 0) return result.stdout;
  const host = folder.remote.host.name;
  switch (result.code) {
    case REMOTE_OUTSIDE:
      throw outsideWorkspace();
    case REMOTE_ROOT_MISSING:
      throw folderNotFound("not_found", folder.path);
    case REMOTE_NOT_REGULAR:
      throw notRegular();
    case 13:
      throw new HostError("no_access", `${host} doesn't let you open this.`, { key: "remote_files_no_access", args: { host } });
    default:
      throw new RemoteExit(result.code, result.stderr.trim());
  }
}

/** Remote folder [requested]'s entries, as [localEntries] gives them. */
async function remoteEntries(folder: RemoteFolder, requested: string, path: string): Promise<Entry[]> {
  const output = await runRemote(folder, [...scopeScript(folder.path, requested), `printf '%s\\0' "$R"`, listingScript(requested)]);
  const end = output.indexOf(0);
  const realRoot = output.subarray(0, end).toString("utf8");
  return parseListing(output.subarray(end + 1), requested).flatMap((entry): Entry[] => {
    if (entry.symlink && (entry.target === null || !inside(realRoot, entry.target))) return [];
    return [
      {
        name: entry.name,
        path: childPath(path, entry.name),
        kind: entry.kind === "directory" ? "directory" : "file",
        size: entry.size,
        modified: Math.floor(entry.mtimeMs),
      },
    ];
  });
}

/** Remote file [requested]'s size, and the bytes [bytesNeeded] asks for, read there so the rest never travels. */
async function remoteRead(folder: RemoteFolder, requested: string, image: boolean): Promise<{ size: number; bytes: Buffer }> {
  const limit = image ? IMAGE_LIMIT : TEXT_LIMIT;
  const output = await runRemote(folder, [
    ...scopeScript(folder.path, requested),
    `[ -d "$T" ] && exit 21`,
    `[ -f "$T" ] || exit ${String(REMOTE_NOT_REGULAR)}`,
    `[ -r "$T" ] || exit 13`,
    `if stat -c %s / >/dev/null 2>&1; then s=$(stat -L -c %s -- "$T"); else s=$(stat -L -f %z -- "$T"); fi || exit 13`,
    // Past the limit, what [bytesNeeded] wants of any larger file.
    `if [ "$s" -gt ${String(limit)} ]; then n=${String(bytesNeeded(limit + 1, image))}; else n=$s; fi`,
    `printf '%s\\n' "$s"`,
    `[ "$n" -gt 0 ] || exit 0`,
    `exec head -c "$n" -- "$T"`,
  ]);
  const end = output.indexOf(10);
  return { size: Number(output.subarray(0, end).toString("utf8")), bytes: output.subarray(end + 1) };
}

/** Makes folder [name] in remote folder [parent]. */
async function remoteMakeFolder(folder: RemoteFolder, parent: string, name: string): Promise<void> {
  try {
    await runRemote(folder, [
      ...scopeScript(folder.path, parent),
      `[ -d "$T" ] || exit 20`,
      `n=${quote(name)}`,
      `[ -e "$T/$n" ] || [ -L "$T/$n" ] && exit ${String(REMOTE_NAME_TAKEN)}`,
      `mkdir -- "$T/$n" 2>/dev/null && exit 0`,
      `[ -e "$T/$n" ] && exit ${String(REMOTE_NAME_TAKEN)}`,
      `[ -w "$T" ] || exit 13`,
      "exit 1",
    ]);
  } catch (error) {
    if (error instanceof RemoteExit && error.status === REMOTE_NAME_TAKEN) throw nameTaken(name);
    throw error;
  }
}

/**
 * Long-lived workspace file access for the app's file browser: sends `pipkin_files_ready`, then answers
 * concurrently, echoing each request's `id`, until the app hangs up:
 * - `pipkin_files_list {root, path}` with `pipkin_files_listing {path, entries}`, each entry
 *   `{name, path, kind: "file" | "directory", size, modified}` (epoch ms), folders first. Links are
 *   followed; dangling ones and those leading out of the workspace are left out.
 * - `pipkin_files_read {root, path}` with `pipkin_files_file {path, size, kind, content?}`: `image`
 *   (base64), `text` (UTF-8), `binary` or `too_large`, the last two without content.
 * - `pipkin_files_mkdir {root, path, name}` with `pipkin_files_made {path}` naming the new folder.
 * `root` is absolute; every other path is relative to it, `/`-separated, `.` for the root itself, and
 * may not leave it. Failures are an `error` in the reply, with its `errorI18n`, so the browser can show them in place.
 * A remote workspace's stand-in as `root` works on the remote folder it names, with the same rules; a failed
 * connection adds its code as `errorCode`.
 */
export async function files(ctx: ModeContext): Promise<JsonObject> {
  ctx.conn.send({ type: "pipkin_files_ready" });
  ctx.conn.listen((message) => {
    const { id, root, path } = message;
    if (typeof id !== "number" || typeof root !== "string" || typeof path !== "string") return;
    if (message.type === "pipkin_files_list") {
      void list(ctx.env, root, path).then((result) => {
        ctx.conn.send({ type: "pipkin_files_listing", id, ...result });
      });
    } else if (message.type === "pipkin_files_read") {
      void read(ctx.env, root, path).then((result) => {
        ctx.conn.send({ type: "pipkin_files_file", id, ...result });
      });
    } else if (message.type === "pipkin_files_mkdir" && typeof message.name === "string") {
      void makeFolder(ctx.env, root, path, message.name).then((result) => {
        ctx.conn.send({ type: "pipkin_files_made", id, ...result });
      });
    }
  });
  if (!ctx.signal.aborted) {
    await new Promise<void>((done) => {
      ctx.signal.addEventListener("abort", () => done(), { once: true });
    });
  }
  return {};
}
