import type { Dirent, Stats } from "node:fs";
import { mkdir, open, readdir, realpath, stat } from "node:fs/promises";
import { extname, isAbsolute, join, relative, resolve } from "node:path";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";

/** Escaping can double a text file's size in JSON; 2 MiB stays well under the app's 16 MiB line limit. */
const TEXT_LIMIT = 2 * 1024 * 1024;
/** Base64 inflates by a third, so 8 MiB of image is under 11 MiB on the line. */
const IMAGE_LIMIT = 8 * 1024 * 1024;
/** Enough of an oversized file to tell binary from text, so the app can say which it is. */
const SNIFF_BYTES = 8 * 1024;
/** Paseo's threshold: text has few control characters besides tab, LF and CR. */
const CONTROL_RATIO = 0.3;
const IMAGE_EXTENSIONS: Record<string, true> = { ".png": true, ".jpg": true, ".jpeg": true, ".gif": true, ".webp": true, ".bmp": true };

const OUTSIDE = "That path is outside the workspace.";
const MESSAGES: Record<string, string> = {
  EACCES: "Termux can't open this.",
  EPERM: "Termux can't open this.",
  ENOENT: "This file or folder no longer exists.",
  ENOTDIR: "This isn't a folder.",
  EISDIR: "This is a folder.",
  ELOOP: "This link goes round in circles.",
  EROFS: "This storage is read-only.",
  ENOSPC: "The device is full.",
};
/** Entries that vanished, or links that dangle or loop, between readdir and stat. */
const MISSING: Record<string, true> = { ENOENT: true, ENOTDIR: true, ELOOP: true };

/** A failure whose message is already fit to show. */
class Refusal extends Error {}

function errorCode(error: unknown): string | undefined {
  return error instanceof Error && "code" in error && typeof error.code === "string" ? error.code : undefined;
}

function describe(error: unknown): string {
  if (error instanceof Refusal) return error.message;
  const code = errorCode(error);
  return (code === undefined ? undefined : MESSAGES[code]) ?? `Can't read this (${code ?? "unknown error"}).`;
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

/** Resolves [raw] under [root], refusing anything that leaves it by `..` or through a link. */
async function scope(root: string, raw: string): Promise<Scoped> {
  let realRoot: string;
  try {
    if (!isAbsolute(root)) throw new Refusal();
    realRoot = await realpath(root);
    if (!(await stat(realRoot)).isDirectory()) throw new Refusal();
  } catch {
    throw new Refusal(`Folder not found: ${root}`);
  }
  const requested = resolve(root, raw);
  if (!inside(root, requested)) throw new Refusal(OUTSIDE);
  const real = await realpath(requested);
  if (!inside(realRoot, real)) throw new Refusal(OUTSIDE);
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

async function list(root: string, raw: string): Promise<JsonObject> {
  let path = raw;
  try {
    const dir = await scope(root, raw);
    path = relativeTo(root, dir.requested);
    const dirents = await readdir(dir.real, { withFileTypes: true });
    const found = await Promise.all(
      dirents.map(async (dirent) => {
        const stats = await entryStats(dir, dirent);
        if (stats === undefined) return undefined;
        return {
          name: dirent.name,
          path: path === "." ? dirent.name : `${path}/${dirent.name}`,
          kind: stats.isDirectory() ? "directory" : "file",
          size: stats.size,
          modified: Math.floor(stats.mtimeMs),
        };
      }),
    );
    const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });
    const entries = found
      .filter((entry) => entry !== undefined)
      .toSorted((a, b) => Number(b.kind === "directory") - Number(a.kind === "directory") || collator.compare(a.name, b.name));
    return { path, entries };
  } catch (error) {
    return { path, entries: [], error: describe(error) };
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

async function read(root: string, raw: string): Promise<JsonObject> {
  let path = raw;
  try {
    const file = await scope(root, raw);
    path = relativeTo(root, file.requested);
    const stats = await stat(file.real);
    if (stats.isDirectory()) throw new Refusal("This is a folder.");
    // Opening a FIFO or device would block or never end.
    if (!stats.isFile()) throw new Refusal("This isn't a regular file.");
    const size = stats.size;
    if (IMAGE_EXTENSIONS[extname(file.requested).toLowerCase()] === true) {
      if (size > IMAGE_LIMIT) return { path, size, kind: "too_large" };
      const bytes = await readStart(file.real, size);
      return { path, size: bytes.length, kind: "image", content: bytes.toString("base64") };
    }
    if (size > TEXT_LIMIT) {
      const kind = asText(await readStart(file.real, SNIFF_BYTES), true) === undefined ? "binary" : "too_large";
      return { path, size, kind };
    }
    const bytes = await readStart(file.real, size);
    const content = asText(bytes, false);
    return content === undefined ? { path, size: bytes.length, kind: "binary" } : { path, size: bytes.length, kind: "text", content };
  } catch (error) {
    return { path, error: describe(error) };
  }
}

async function makeFolder(root: string, raw: string, rawName: string): Promise<JsonObject> {
  try {
    const name = rawName.trim();
    if (name === "" || name === "." || name === ".." || name.includes("/") || name.includes("\0")) {
      throw new Refusal("Folder names can't be empty, contain / or be . or ..");
    }
    const parent = await scope(root, raw);
    try {
      await mkdir(join(parent.real, name));
    } catch (error) {
      if (errorCode(error) === "EEXIST") throw new Refusal(`A file or folder named ${name} already exists.`);
      throw error;
    }
    return { path: relativeTo(root, join(parent.requested, name)) };
  } catch (error) {
    return { error: describe(error) };
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
 * may not leave it. Failures are an `error` in the reply, so the browser can show them in place.
 */
export async function files(ctx: ModeContext): Promise<JsonObject> {
  ctx.conn.send({ type: "pipkin_files_ready" });
  ctx.conn.listen((message) => {
    const { id, root, path } = message;
    if (typeof id !== "number" || typeof root !== "string" || typeof path !== "string") return;
    if (message.type === "pipkin_files_list") {
      void list(root, path).then((result) => {
        ctx.conn.send({ type: "pipkin_files_listing", id, ...result });
      });
    } else if (message.type === "pipkin_files_read") {
      void read(root, path).then((result) => {
        ctx.conn.send({ type: "pipkin_files_file", id, ...result });
      });
    } else if (message.type === "pipkin_files_mkdir" && typeof message.name === "string") {
      void makeFolder(root, path, message.name).then((result) => {
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
