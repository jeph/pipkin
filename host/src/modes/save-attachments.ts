import { randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { basename, join, posix } from "node:path";
import { HostError } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { nullableStringParam } from "../params.js";
import { remoteFailed } from "../remote/errors.js";
import { RemoteFileError, writeRemoteFile } from "../remote/fs.js";
import { findHost } from "../remote/hosts.js";
import { remotePaths } from "../remote/paths.js";
import { type Remote, runScript } from "../remote/ssh.js";
import { readStandIn } from "../remote/stand-ins.js";
import { quote } from "../remote/script.js";

const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;

/** A plain file name: no directories, no NULs, never `.`/`..` or hidden. */
function safeName(name: string): string {
  const base = basename(name.replaceAll("\\", "/")).replaceAll("\0", "").replace(/^\.+/, "");
  return base === "" ? "file" : base;
}

/** `report.pdf`, `report-2.pdf`, … so two picks with one name both survive. */
function uniqueName(name: string, taken: Set<string>): string {
  if (!taken.has(name)) return name;
  const dot = name.lastIndexOf(".");
  const [stem, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ""];
  for (let n = 2; ; n++) {
    const candidate = `${stem}-${n}${ext}`;
    if (!taken.has(candidate)) return candidate;
  }
}

/** The files a request attaches, under names unique among them. */
function attachedFiles(params: JsonObject): { name: string; bytes: Buffer }[] {
  const files = params.files;
  if (!Array.isArray(files) || files.length === 0) throw new HostError("bad_params", 'Parameter "files" must be a non-empty array');
  const taken = new Set<string>();
  return files.map((file) => {
    if (!isObject(file) || typeof file.name !== "string" || typeof file.data !== "string" || !BASE64.test(file.data)) {
      throw new HostError("bad_params", "Each file needs a name and base64 data");
    }
    const name = uniqueName(safeName(file.name), taken);
    taken.add(name);
    return { name, bytes: Buffer.from(file.data, "base64") };
  });
}

/** A fresh folder's name: when it was made, and a few random digits. */
function folderName(): string {
  const stamp = new Date().toISOString().replaceAll(/[-:]/g, "").replace(/\..*$/, "");
  return `${stamp}-${randomBytes(3).toString("hex")}`;
}

/**
 * Makes a fresh folder under the remote's `${XDG_CACHE_HOME:-~/.cache}/pipkin/attachments` and returns its path, as
 * the remote's shell expands it.
 */
async function remoteFolder(remote: Remote): Promise<string> {
  const script = [
    `d="\${XDG_CACHE_HOME:-$HOME/.cache}/pipkin/attachments/"${quote(folderName())}`,
    `mkdir -p -- "$d" || exit 1`,
    `printf '%s' "$d"`,
  ].join("\n");
  const result = await runScript(remote, script);
  if (result.code !== 0) throw remoteFailed(remote.host.name, result.stderr.trim() || `mkdir exited with ${String(result.code)}`);
  return result.stdout.toString("utf8");
}

/**
 * Writes files attached in the app where Pi's tools can read them; Pi's RPC `prompt` itself only carries images. For
 * a chat on the phone that's a fresh folder under `$PIPKIN_ROOT/attachments`. For a remote chat, whose `cwd` is a
 * stand-in, the tools work on the remote, so the files go to a fresh folder in its cache, and the paths are the
 * remote's.
 */
export async function saveAttachments(ctx: ModeContext): Promise<JsonObject> {
  const files = attachedFiles(ctx.params);
  const cwd = nullableStringParam(ctx.params, "cwd");
  const paths = remotePaths(ctx.env.configDir, ctx.env.prefix, ctx.env.home);
  const standIn = cwd === null ? null : await readStandIn(paths.remotes, cwd);
  if (standIn !== null) {
    const remote = { paths, host: await findHost(paths, standIn.hostId) };
    const dir = await remoteFolder(remote);
    const saved: string[] = [];
    for (const file of files) {
      const path = posix.join(dir, file.name);
      // A refused write comes back as the app's `remote_failed`, with the remote's reason as its detail.
      await writeRemoteFile(remote, path, file.bytes).catch((error: unknown) => {
        throw error instanceof RemoteFileError ? remoteFailed(remote.host.name, error.message) : error;
      });
      saved.push(path);
    }
    return { paths: saved };
  }

  const dir = join(ctx.env.root, "attachments", folderName());
  await mkdir(dir, { recursive: true });
  const saved: string[] = [];
  for (const file of files) {
    const path = join(dir, file.name);
    await writeFile(path, file.bytes, { flag: "wx" });
    saved.push(path);
  }
  return { paths: saved };
}
