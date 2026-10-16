import { cp, mkdir, open, readdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { basename, dirname, join, relative } from "node:path";
import { HostError, type HostEnv } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { waitForPiExit } from "../pi-processes.js";
import { withConfigLock } from "../config-lock.js";

/**
 * Where the move stands: absent before it starts, `copied` once the config folder holds the copy (the shared-storage
 * original still exists until Pipkin has updated the session paths it keeps), `done` once the original is gone.
 */
interface MoveRecord {
  from: string;
  phase: "copied" | "done";
}

/** `copy`: the shared-storage folder still needs copying. `finish`: it is copied and waits to be deleted. */
export type MoveState = "none" | "copy" | "finish";

/** Session headers are one JSON line; a longer first line is no header Pipkin rewrites. */
const MAX_HEADER = 1024 * 1024;

async function readRecord(env: HostEnv): Promise<MoveRecord | null> {
  try {
    const record = JSON.parse(await readFile(join(env.root, "config-move.json"), "utf8")) as MoveRecord;
    return record.phase === "copied" || record.phase === "done" ? record : null;
  } catch {
    return null;
  }
}

async function writeRecord(env: HostEnv, record: MoveRecord): Promise<void> {
  const path = join(env.root, "config-move.json");
  await writeFile(`${path}.tmp`, JSON.stringify(record));
  await rename(`${path}.tmp`, path);
}

async function isDirectory(path: string): Promise<boolean> {
  return stat(path).then(
    (stats) => stats.isDirectory(),
    () => false,
  );
}

/**
 * Whether the config folder still has to move off shared storage. A folder that reappears on shared storage after a
 * finished move is left alone.
 */
export async function configMoveState(env: HostEnv): Promise<MoveState> {
  if (env.legacyConfigDir === undefined) return "none";
  const record = await readRecord(env);
  if (record?.phase === "done" || !(await isDirectory(env.legacyConfigDir))) return "none";
  return record?.phase === "copied" ? "finish" : "copy";
}

/** Session files under `dir`, relative to it, such as `sessions/--home--/2026….jsonl`. */
async function sessionFiles(dir: string): Promise<string[]> {
  const sessions = join(dir, "sessions");
  const entries = await readdir(sessions, { recursive: true, withFileTypes: true }).catch(() => []);
  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".jsonl"))
    .map((entry) => relative(dir, join(entry.parentPath, entry.name)));
}

/** Every file's size by path relative to `dir`, and every folder (size -1), for checking a copy. */
async function inventory(dir: string): Promise<Map<string, number>> {
  const files = new Map<string, number>();
  for (const entry of await readdir(dir, { recursive: true, withFileTypes: true })) {
    const path = join(entry.parentPath, entry.name);
    files.set(relative(dir, path), entry.isFile() ? (await stat(path)).size : -1);
  }
  return files;
}

/** Points a forked session's `parentSession` at the moved copy of its parent. */
async function moveParentSession(file: string, from: string, to: string): Promise<void> {
  const handle = await open(file, "r");
  let header: string;
  try {
    const { buffer, bytesRead } = await handle.read(Buffer.alloc(MAX_HEADER), 0, MAX_HEADER, 0);
    const end = buffer.subarray(0, bytesRead).indexOf("\n");
    if (end < 0) return;
    header = buffer.subarray(0, end).toString("utf8");
  } finally {
    await handle.close();
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(header);
  } catch {
    return;
  }
  if (parsed === null || typeof parsed !== "object" || !("parentSession" in parsed)) return;
  const parent = parsed.parentSession;
  if (typeof parent !== "string" || !parent.startsWith(`${from}/`)) return;
  const moved = `${JSON.stringify({ ...parsed, parentSession: to + parent.slice(from.length) })}\n`;
  const content = await readFile(file);
  await writeFile(`${file}.tmp`, Buffer.concat([Buffer.from(moved), content.subarray(Buffer.byteLength(header) + 1)]));
  await rename(`${file}.tmp`, file);
}

/** Copies the shared-storage config folder into place, keeping any config folder already there under another name. */
async function copy(ctx: ModeContext, from: string): Promise<void> {
  const to = ctx.env.configDir;
  const parent = dirname(to);
  await mkdir(parent, { recursive: true });
  for (const leftover of await readdir(parent)) {
    if (leftover.startsWith(`${basename(to)}.moving-`)) await rm(join(parent, leftover), { recursive: true, force: true });
  }
  await waitForPiExit(from, ctx.signal);
  const staging = `${to}.moving-${process.pid}`;
  try {
    let copied = 0;
    let reported = 0;
    await cp(from, staging, {
      recursive: true,
      errorOnExist: true,
      force: false,
      preserveTimestamps: true,
      verbatimSymlinks: true,
      filter: () => {
        copied++;
        if (Date.now() - reported > 500) {
          reported = Date.now();
          ctx.conn.send({ type: "pipkin_progress", line: `Copied ${copied} files` });
        }
        return true;
      },
    });
    const [original, copyOf] = await Promise.all([inventory(from), inventory(staging)]);
    for (const [path, size] of original) {
      if (copyOf.get(path) !== size) throw new HostError("move_failed", `The copy of ${path} does not match the original`);
    }
    for (const session of await sessionFiles(staging)) await moveParentSession(join(staging, session), from, to);
    if (await isDirectory(to)) await rename(to, `${to}.before-move-${Date.now()}`);
    await rename(staging, to);
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    throw error;
  }
  await writeRecord(ctx.env, { from, phase: "copied" });
}

/**
 * Moves Pi's config folder off shared storage into `$HOME/.pipkin/pi`, in two steps around Pipkin updating the session
 * paths it keeps. Without `finish` it copies the folder (unless already copied) and reports the session files, so
 * Pipkin can update the paths it keeps; with `finish` it deletes the shared-storage original. Other modes refuse to run
 * until both steps are done.
 */
export async function moveConfig(ctx: ModeContext): Promise<JsonObject> {
  const finish = ctx.params.finish === true;
  const from = ctx.env.legacyConfigDir;
  const to = ctx.env.configDir;
  return withConfigLock(ctx.env, async () => {
    const state = await configMoveState(ctx.env);
    if (from === undefined || state === "none") return { from: null, to, sessions: [] };
    if (!finish) {
      if (state === "copy") await copy(ctx, from);
      return { from, to, sessions: await sessionFiles(to) };
    }
    if (state === "copy") throw new HostError("move_failed", "The config folder has not been copied yet");
    await waitForPiExit(from, ctx.signal);
    await rm(from, { recursive: true, force: true });
    await writeRecord(ctx.env, { from, phase: "done" });
    return { from, to, sessions: [] };
  });
}
