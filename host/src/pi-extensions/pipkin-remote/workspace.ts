/**
 * Whether a chat's folder is a remote workspace, and what it stands for, for the Pipkin extensions that work
 * differently there: `pipkin-remote` itself, `pipkin-background` and `pipkin-subagents`. Each is bundled with its own
 * copy of this module.
 */
import os from "node:os";
import { posix, resolve } from "node:path";
import { getAgentDir } from "@earendil-works/pi-coding-agent";
import { findHost } from "../../remote/hosts.js";
import { remotePaths, type RemotePaths } from "../../remote/paths.js";
import { readStandIn } from "../../remote/stand-ins.js";
import type { Remote } from "../../remote/ssh.js";

/** Where Termux keeps its packages, for a Pi whose environment somehow lost `$PREFIX`. */
const TERMUX_PREFIX = "/data/data/com.termux/files/usr";

/** A chat's remote folder: the host and its SSH state, the local stand-in Pi works in, and the remote folder. */
export interface RemoteWorkspace extends Remote {
  /** The stand-in folder, Pi's working directory on the phone. */
  standIn: string;
  /** The remote folder, absolute. */
  path: string;
}

/** Pipkin's SSH state for this Pi, beside its config folder as the host keeps it. */
export function piRemotePaths(): RemotePaths {
  return remotePaths(getAgentDir(), process.env.PREFIX || TERMUX_PREFIX, process.env.HOME || os.homedir());
}

/**
 * The remote workspace [cwd] stands in for, or null when it's a local folder. Throws `remote_host_unknown` when the
 * stand-in's host was removed: such a chat must never fall back to working on the empty stand-in.
 */
export async function remoteWorkspace(cwd: string): Promise<RemoteWorkspace | null> {
  const paths = piRemotePaths();
  const standIn = await readStandIn(paths.remotes, cwd);
  if (standIn === null) return null;
  return { paths, host: await findHost(paths, standIn.hostId), standIn: resolve(cwd), path: standIn.path };
}

/** [local] with the stand-in folder at its start swapped for the remote folder; any other path as it is. */
export function toRemotePath(workspace: RemoteWorkspace, local: string): string {
  if (local === workspace.standIn) return workspace.path;
  if (!local.startsWith(`${workspace.standIn}/`)) return local;
  return posix.join(workspace.path, local.slice(workspace.standIn.length + 1));
}

/**
 * [path] with a leading `~` (`~` or `~/…`, after Pi's optional `@`) made [home]: Pi would expand it to the phone's
 * home folder. Undefined when [path] has none.
 */
export function expandRemoteHome(path: string, home: string): string | undefined {
  const bare = path.startsWith("@") ? path.slice(1) : path;
  if (bare === "~") return home;
  if (bare.startsWith("~/")) return posix.join(home, bare.slice(2));
  return undefined;
}
