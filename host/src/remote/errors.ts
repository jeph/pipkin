import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";

/** The `code` a Node error carries: an errno name such as `ENOENT`, or a child's exit status from `execFile`. */
export function errorCode(error: unknown): unknown {
  return error instanceof Error && "code" in error ? error.code : undefined;
}

/** What a failure's classification depends on: who the host is, and whether `ssh` was allowed to ask the user. */
export interface FailureContext {
  /** The host's name, as the app shows it. */
  name: string;
  auth: "key" | "password" | "config";
  /** True for `remote_connect`, where `ssh` prompts; false for every background call, which runs in batch mode. */
  interactive: boolean;
}

export function sshMissing(): HostError {
  return new HostError("ssh_missing", "OpenSSH isn't installed in Termux.", { key: "remote_ssh_missing" });
}

export function hostUnknown(): HostError {
  return new HostError("remote_host_unknown", "This workspace's host was removed.", { key: "remote_host_unknown" });
}

export function bashMissing(name: string): HostError {
  return new HostError("remote_bash_missing", `${name} has no bash, which Pipkin needs to work there.`, {
    key: "remote_bash_missing",
    args: { host: name },
  });
}

export function connectCancelled(): HostError {
  return new HostError("remote_connect_cancelled", "Connecting was cancelled.", { key: "remote_connect_cancelled" });
}

/** The server refused Pipkin's key, the password, or the key whose passphrase was asked for. */
export function authFailed(name: string): HostError {
  return new HostError("remote_auth_failed", `${name} refused the sign-in.`, { key: "remote_auth_failed", args: { host: name } });
}

/** A failure the app shows as is: the shape of `error` in `pipkin_places` and on the channel's `remote` topic. */
export function errorJson(error: HostError): JsonObject {
  return { code: error.code, message: error.message, i18n: error.i18n };
}

const HOST_KEY_CHANGED = /^Host key for .* has changed/;
const HOST_KEY_UNKNOWN = /^No \S+ host key is known for |^Host key verification failed\./;
const AUTH_REFUSED = /: Permission denied \(|^Too many authentication failures|^Received disconnect from .*: Too many authentication failures/;
/** Why the network failed: refused, timed out, no route, name unknown, dropped. */
const UNREACHABLE =
  /Connection refused|Connection timed out|Operation timed out|No route to host|Network is unreachable|Network is down|Host is down|Could not resolve hostname|Name or service not known|Temporary failure in name resolution|nodename nor servname|No address associated with hostname|Timeout, server .* not responding|Connection reset|Connection closed by|closed by remote host|Broken pipe|banner exchange|Software caused connection abort/;
/**
 * Lines only `ssh` itself writes. Classification reads these alone, so the remote command's own output (a `git fetch`
 * that failed to reach its server, say) is never taken for this connection's failure.
 */
const SSH_OWN_LINE =
  /^(ssh: |ssh_\w+|kex_\w+|mux_client_\w+|muxclient|Control ?[Ss]ocket|client_loop: |Bad (owner|configuration)|Connection |Host key |No \S+ host key |Too many authentication|Received disconnect|Load key |\S+: Permission denied \(|\S+ line \d+: |Unable to negotiate|command-line line \d+: |Timeout, server)/;
/** What `ssh` adds that never says why it failed. */
const NOISE = /^(Warning: |\*\* |@|$)/;

/** [stderr]'s last line that says something, which is how `ssh` ends a failure; empty when there's none. */
export function lastLine(stderr: string): string {
  const lines = stderr.split(/\r?\n/).map((line) => line.trim());
  return lines.findLast((line) => !NOISE.test(line)) ?? "";
}

/**
 * The [HostError] for an `ssh` run that exited with 255, from what it wrote to [stderr]; undefined when the 255 came
 * from the remote command instead, which `ssh` passes through. A refused sign-in in batch mode means the user is needed
 * (`remote_not_connected`) unless the host signs in with Pipkin's own key, which never needs them.
 */
export function classifySshFailure(stderr: string, context: FailureContext): HostError | undefined {
  const host = context.name;
  const own = stderr.split(/\r?\n/).filter((line) => SSH_OWN_LINE.test(line));
  if (own.length === 0) return undefined;
  if (own.some((line) => HOST_KEY_CHANGED.test(line))) {
    return new HostError("remote_host_key_changed", `${host}'s host key has changed. Someone may be intercepting the connection.`, {
      key: "remote_host_key_changed",
      args: { host },
    });
  }
  if (own.some((line) => HOST_KEY_UNKNOWN.test(line))) {
    return new HostError("remote_host_key_unknown", `${host}'s host key isn't known yet. Connect to check it.`, {
      key: "remote_host_key_unknown",
      args: { host },
    });
  }
  if (own.some((line) => AUTH_REFUSED.test(line))) {
    if (context.interactive || context.auth === "key") return authFailed(host);
    return new HostError("remote_not_connected", `Not connected to ${host}. Connect to sign in.`, {
      key: "remote_not_connected",
      args: { host },
    });
  }
  const line = own.find((candidate) => UNREACHABLE.test(candidate));
  if (line !== undefined) return unreachable(host, line.trim());
  return remoteFailed(host, lastLine(own.join("\n")));
}

/** [host] couldn't be reached, for the reason [detail] gives, `ssh`'s own line where it has one. */
export function unreachable(host: string, detail: string): HostError {
  return new HostError("remote_unreachable", `Couldn't reach ${host}: ${detail}`, { key: "remote_unreachable", args: { host, detail } });
}

/**
 * The server kept refusing [host]'s master a session past its `MaxSessions`, as when terminals and background tasks
 * hold them all, for as long as a call waits for one.
 */
export function sessionsFull(host: string): HostError {
  return new HostError("remote_sessions_full", `${host} has no free sessions. Close a terminal or stop a background task, then try again.`, {
    key: "remote_sessions_full",
    args: { host },
  });
}

/** Any other failure of `ssh` with [host], with `ssh`'s own line as [detail]. */
export function remoteFailed(host: string, detail: string): HostError {
  return new HostError("remote_failed", `SSH to ${host} failed: ${detail}`, { key: "remote_failed", args: { host, detail } });
}

/**
 * How shells say `bash` isn't there when the command wrapper `exec`s it: dash, busybox and bash's `sh`
 * (`exec: bash: not found`, `sh: bash: not found`), zsh (`command not found: bash`), fish (`Unknown command: bash`)
 * and csh (`bash: Command not found.`). A command that bash runs and can't find reads `bash: line 1: x: command not
 * found`, which none of these match.
 */
const NO_BASH = /(^|[\s:])bash: ([Cc]ommand )?not found|command not found: bash|Unknown command:? '?bash\b|exec: bash: No such file/m;

/** Whether a run that exited with [code] failed because the remote has no `bash` to run the wrapper. */
export function isBashMissing(code: number | null, stderr: string): boolean {
  return code === 127 && NO_BASH.test(stderr);
}
