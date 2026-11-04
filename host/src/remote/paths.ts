import { dirname, join } from "node:path";

/**
 * Where Pipkin keeps its SSH state and which OpenSSH it runs: everything a remote operation needs from Termux. Built
 * from plain values, so Pi extensions can use it as well as host modes.
 */
export interface RemotePaths {
  /** `ssh/` beside Pi's config folder: `hosts.json`, the generated config, Pipkin's key, known hosts, sockets. */
  ssh: string;
  /** `remotes/` beside Pi's config folder: the stand-in folders of remote workspaces. */
  remotes: string;
  /** Termux's `$PREFIX/bin`, holding `ssh`, `ssh-keygen`, `pkg` and `sh`. */
  bin: string;
  /**
   * The home folder the host runs with (`$HOME`), whose `.ssh/config` and `.ssh/known_hosts` the generated config
   * names. OpenSSH would expand `~` from the passwd entry instead, which is the same in Termux but not in tests.
   */
  home: string;
}

/** The paths for Pi's config folder [configDir] (`PI_CODING_AGENT_DIR`), Termux's `$PREFIX` [prefix] and `$HOME` [home]. */
export function remotePaths(configDir: string, prefix: string, home: string): RemotePaths {
  const state = dirname(configDir);
  return { ssh: join(state, "ssh"), remotes: join(state, "remotes"), bin: join(prefix, "bin"), home };
}

export function hostsFile(paths: RemotePaths): string {
  return join(paths.ssh, "hosts.json");
}

export function sshConfigFile(paths: RemotePaths): string {
  return join(paths.ssh, "config");
}

export function keyFile(paths: RemotePaths): string {
  return join(paths.ssh, "id_ed25519");
}

export function knownHostsFile(paths: RemotePaths): string {
  return join(paths.ssh, "known_hosts");
}

/** The folder of control sockets, kept short: OpenSSH adds 17 characters while it makes one, and `sun_path` is small. */
export function socketsDir(paths: RemotePaths): string {
  return join(paths.ssh, "s");
}

/** Host [hostId]'s master connection socket. */
export function controlPath(paths: RemotePaths, hostId: string): string {
  return join(socketsDir(paths), hostId);
}
