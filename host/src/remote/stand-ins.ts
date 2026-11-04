import { createHash } from "node:crypto";
import { mkdir, readdir, readFile, realpath } from "node:fs/promises";
import { basename, dirname, join, posix, resolve } from "node:path";
import { HostError } from "../env.js";
import { isObject } from "../framing.js";
import { writeAtomically } from "./hosts.js";

/** A remote workspace: its local stand-in folder, which the app uses as the workspace's `cwd`, and what it stands for. */
export interface StandIn {
  cwd: string;
  hostId: string;
  /** The remote folder, absolute. */
  path: string;
}

const MANIFEST = "remote.json";

/** [path] as stand-ins key it: absolute, normalized, no trailing slash but on `/`. */
export function normalizeRemotePath(path: string): string {
  if (!path.startsWith("/") || path.includes("\0")) throw new HostError("bad_params", `Not an absolute remote path: ${path}`);
  const normal = posix.normalize(path);
  return normal.length > 1 && normal.endsWith("/") ? normal.slice(0, -1) : normal;
}

/** The first 16 hex digits of the SHA-256 of [path], so a remote folder always gets the same stand-in. */
function pathHash(path: string): string {
  return createHash("sha256").update(path).digest("hex").slice(0, 16);
}

/** Where the stand-in for host [hostId]'s folder [path] lives under [remotes] (`remotes/<host id>/<hash>`). */
export function standInFor(remotes: string, hostId: string, path: string): string {
  return join(remotes, hostId, pathHash(normalizeRemotePath(path)));
}

/** Makes the stand-in for host [hostId]'s folder [path] if needed, and returns it. */
export async function createStandIn(remotes: string, hostId: string, path: string): Promise<string> {
  const remotePath = normalizeRemotePath(path);
  const cwd = standInFor(remotes, hostId, remotePath);
  if ((await readStandIn(remotes, cwd))?.path === remotePath) return cwd;
  await mkdir(cwd, { recursive: true });
  await writeAtomically(join(cwd, MANIFEST), `${JSON.stringify({ version: 1, hostId, path: remotePath })}\n`, 0o644);
  return cwd;
}

/**
 * What the stand-in at [cwd] stands for, or null when [cwd] isn't a stand-in. Only a folder in its own place under
 * [remotes] counts, so a local project that happens to hold a `remote.json` never turns remote.
 */
export async function readStandIn(remotes: string, cwd: string): Promise<{ hostId: string; path: string } | null> {
  const folder = resolve(cwd);
  const parent = dirname(dirname(folder));
  if (parent !== resolve(remotes) && parent !== (await realpath(remotes).catch(() => null))) return null;
  let manifest: unknown;
  try {
    manifest = JSON.parse(await readFile(join(folder, MANIFEST), "utf8"));
  } catch {
    return null;
  }
  if (!isObject(manifest) || manifest.version !== 1) return null;
  const { hostId, path } = manifest;
  if (typeof hostId !== "string" || typeof path !== "string" || !path.startsWith("/")) return null;
  if (basename(dirname(folder)) !== hostId || basename(folder) !== pathHash(path)) return null;
  return { hostId, path };
}

/** Every stand-in under [remotes], including those of removed hosts, whose chats still need a label. */
export async function listStandIns(remotes: string): Promise<StandIn[]> {
  const hostIds = await readdir(remotes).catch(() => []);
  const found = await Promise.all(
    hostIds.map(async (hostId) => {
      const hashes = await readdir(join(remotes, hostId)).catch(() => []);
      return Promise.all(
        hashes.map(async (hash) => {
          const cwd = join(remotes, hostId, hash);
          const standIn = await readStandIn(remotes, cwd);
          return standIn === null ? null : { cwd, ...standIn };
        }),
      );
    }),
  );
  return found.flat().filter((standIn) => standIn !== null);
}
