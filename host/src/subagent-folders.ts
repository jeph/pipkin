/**
 * A chat's subagent transcripts, which the Pipkin subagents extension saves in `subagents/<session id>/` beside the
 * chat's own file. Pi's session list reads only the files directly in a sessions folder, so they never show as chats.
 */
import { type FileHandle, open, readdir, realpath } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, sep } from "node:path";
import { HostError } from "./env.js";
import { isObject } from "./framing.js";

/** Session ids as Pi writes them; anything else could name a path outside the chat's folder. */
const SESSION_ID = /^[\w-]+$/;

/** The folder holding the subagent transcripts of the session [id] saved in [sessionPath]. */
export function subagentFolder(sessionPath: string, id: string): string | undefined {
  return SESSION_ID.test(id) ? join(dirname(sessionPath), "subagents", id) : undefined;
}

/**
 * The real path of [folder], refusing one that resolves outside the sessions folder, as a symlinked `subagents`
 * folder could (`outside_sessions`); undefined when it's missing.
 */
export async function containedFolder(configDir: string, folder: string): Promise<string | undefined> {
  let target: string;
  try {
    target = await realpath(folder);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
  const inside = relative(await realpath(join(configDir, "sessions")), target);
  if (inside === "" || inside === ".." || inside.startsWith(`..${sep}`) || isAbsolute(inside)) {
    throw new HostError("outside_sessions", "That folder is not inside Pipkin's sessions folder");
  }
  return target;
}

/** How many subagent transcripts the session [id] in [sessionPath] has. */
export async function countSubagents(sessionPath: string, id: string): Promise<number> {
  const folder = subagentFolder(sessionPath, id);
  if (!folder) return 0;
  return (await transcriptFiles(folder)).length;
}

/** The `*.jsonl` files in [folder], none when it's missing. */
export async function transcriptFiles(folder: string): Promise<string[]> {
  try {
    return (await readdir(folder)).filter((name) => name.endsWith(".jsonl")).map((name) => join(folder, name));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  }
}

/** Bytes of a session file's first line, which holds its header, read before giving up on one. */
const HEADER_BYTES = 64 * 1024;

/** The session id in the header of the session file [path], or undefined when it's gone or has no header. */
export async function sessionId(path: string): Promise<string | undefined> {
  let file: FileHandle;
  try {
    file = await open(path, "r");
  } catch {
    return undefined;
  }
  try {
    const buffer = Buffer.alloc(HEADER_BYTES);
    const { bytesRead } = await file.read(buffer, 0, HEADER_BYTES, 0);
    const line = buffer.subarray(0, bytesRead).toString("utf8").split("\n")[0] ?? "";
    const header: unknown = JSON.parse(line);
    return isObject(header) && header.type === "session" && typeof header.id === "string" ? header.id : undefined;
  } catch {
    return undefined;
  } finally {
    await file.close();
  }
}
