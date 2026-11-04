import { HostError, type HostEnv } from "../env.js";
import { remoteFailed } from "../remote/errors.js";
import { findHost } from "../remote/hosts.js";
import { remotePaths, type RemotePaths } from "../remote/paths.js";
import type { Remote } from "../remote/ssh.js";
import { readStandIn } from "../remote/stand-ins.js";

/** The remote folder a workspace's stand-in names, and the host to reach it through. */
export interface RemoteFolder {
  remote: Remote;
  /** The remote folder, absolute. */
  path: string;
}

/** Pipkin's SSH paths in [env]. */
export function envRemotePaths(env: HostEnv): RemotePaths {
  return remotePaths(env.configDir, env.prefix, env.home);
}

/**
 * The remote folder [cwd] stands for, or null when [cwd] is a local folder. Fails with `remote_host_unknown` when the
 * stand-in's host was removed.
 */
export async function remoteFolder(env: HostEnv, cwd: string): Promise<RemoteFolder | null> {
  const paths = envRemotePaths(env);
  const standIn = await readStandIn(paths.remotes, cwd);
  if (standIn === null) return null;
  return { remote: { paths, host: await findHost(paths, standIn.hostId) }, path: standIn.path };
}

/** [error] as a [HostError] the app can show: an SSH failure as it is, anything else as `remote_failed` on [remote]. */
export function asRemoteError(remote: Remote, error: unknown): HostError {
  if (error instanceof HostError) return error;
  return remoteFailed(remote.host.name, error instanceof Error ? error.message : String(error));
}

/**
 * `{ errorCode }` for a failed connection to a remote (the plan's SSH failures, such as `remote_not_connected`), which a
 * reply adds beside its `error` so the app can offer what fixes it; empty for any other failure.
 */
export function connectionCode(error: HostError): { errorCode?: string } {
  return error.code === "ssh_missing" || error.code.startsWith("remote_") ? { errorCode: error.code } : {};
}
