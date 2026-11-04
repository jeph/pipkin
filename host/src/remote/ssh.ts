import { execFile, spawn } from "node:child_process";
import { constants } from "node:fs";
import { access, rm } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { promisify } from "node:util";
import { type HostError, withoutHostVariables } from "../env.js";
import { bashMissing, classifySshFailure, errorCode, isBashMissing, notConnected, sessionsFull, sshMissing, unreachable } from "./errors.js";
import { configWord, destination, type Host } from "./hosts.js";
import { controlPath, knownHostsFile, type RemotePaths, socketsDir, sshConfigFile } from "./paths.js";
import { quote, remoteScript, watched } from "./script.js";

const run = promisify(execFile);

/** One host to run on, and where Pipkin's SSH state is. */
export interface Remote {
  paths: RemotePaths;
  host: Host;
}

/**
 * Concurrent SSH sessions each process opens per host, so one process's calls leave room for the others' below the
 * server's `MaxSessions` (10 by default); the rest wait their turn. Past `MaxSessions` the server refuses a session,
 * and the call waits and asks again ([runSession]).
 */
export const MAX_SESSIONS_PER_HOST = 4;

interface Slots {
  active: number;
  waiting: (() => void)[];
}

const slots = new Map<string, Slots>();

/**
 * Waits for one of the [MAX_SESSIONS_PER_HOST] session slots for host [hostId], and returns the function that frees
 * it. Rejects with [signal]'s reason when it aborts first.
 */
export async function acquireSession(hostId: string, signal?: AbortSignal): Promise<() => void> {
  signal?.throwIfAborted();
  let slot = slots.get(hostId);
  if (slot === undefined) {
    slot = { active: 0, waiting: [] };
    slots.set(hostId, slot);
  }
  const held = slot;
  if (held.active < MAX_SESSIONS_PER_HOST) {
    held.active++;
  } else {
    const { promise, resolve, reject } = Promise.withResolvers<void>();
    const onAbort = (): void => {
      held.waiting.splice(held.waiting.indexOf(wake), 1);
      reject(signal?.reason);
    };
    const wake = (): void => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    };
    held.waiting.push(wake);
    signal?.addEventListener("abort", onAbort, { once: true });
    await promise;
  }
  let released = false;
  return () => {
    if (released) return;
    released = true;
    // A waiter takes the slot over as it is, so nothing can slip in between.
    const next = held.waiting.shift();
    if (next !== undefined) return next();
    held.active--;
    if (held.active === 0) slots.delete(hostId);
  };
}

export function sshBinary(paths: RemotePaths): string {
  return join(paths.bin, "ssh");
}

/** Termux's `sh`, which runs [sessionScript]s and the fallback `ProxyCommand` of [masterOptions]. */
export function shBinary(paths: RemotePaths): string {
  return join(paths.bin, "sh");
}

/** Whether Termux has OpenSSH. */
export async function sshInstalled(paths: RemotePaths): Promise<boolean> {
  try {
    await access(sshBinary(paths), constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

/** The options every `ssh` call shares: Pipkin's config and the host's master connection. */
function connectionArgs(remote: Remote): string[] {
  return ["-F", sshConfigFile(remote.paths), "-o", `ControlPath=${configWord(controlPath(remote.paths, remote.host.id))}`];
}

/** [sshArgs] up to the destination: a call sharing [remote]'s master connection or starting one. */
function directOptions(remote: Remote, interactive: boolean): string[] {
  return [
    ...connectionArgs(remote),
    "-o",
    "ControlMaster=auto",
    "-o",
    "ControlPersist=10m",
    "-o",
    `BatchMode=${interactive ? "no" : "yes"}`,
    "-o",
    `StrictHostKeyChecking=${interactive ? "ask" : "yes"}`,
    ...(interactive ? ["-o", "NumberOfPasswordPrompts=1"] : []),
    destination(remote.host),
  ];
}

/**
 * The arguments that run remote command line [command] on [remote], sharing its master connection or starting one.
 * Background calls never prompt and refuse unknown host keys; an [interactive] call (`remote_connect`) asks through
 * `SSH_ASKPASS`, and asks for a password only once, so a wrong one ends the connect instead of asking again.
 */
export function sshArgs(remote: Remote, command: string, interactive = false): string[] {
  return [...directOptions(remote, interactive), "--", command];
}

/** The variable naming the file a [masterOptions] call creates when its master didn't carry the session. */
const FALLBACK_FILE = "PIPKIN_SSH_FALLBACK";

/**
 * Options, up to the destination, of a call only [remote]'s running master may carry. `ssh` that can't use the master,
 * because the server refused it one more session past its `MaxSessions` or the master is gone, falls back to a
 * connection of its own, which a password host can't sign in on; the `ProxyCommand` that connection would go through
 * only creates the file `$PIPKIN_SSH_FALLBACK` names, so the call ends at once with nothing run on the remote, and the
 * caller can tell. `ssh`'s own messages stay out of the command's stderr: a session the master carries has none, and
 * the fallback's mean nothing. The `ProxyCommand` runs in `$SHELL`, which the caller sets to `sh` ([masterEnvironment]).
 */
function masterOptions(remote: Remote): string[] {
  return [
    ...connectionArgs(remote),
    "-o",
    "ControlMaster=no",
    "-o",
    `ProxyCommand=touch "$${FALLBACK_FILE}"`,
    "-o",
    "LogLevel=QUIET",
    "-o",
    "BatchMode=yes",
    "-o",
    "StrictHostKeyChecking=yes",
    destination(remote.host),
  ];
}

/** [args] as `sh` words. */
function words(args: readonly string[]): string {
  return args.map(quote).join(" ");
}

/** How long a session the master couldn't carry waits before asking again, doubling up to [RETRY_MAX_MS]. */
const RETRY_FIRST_MS = 250;
const RETRY_MAX_MS = 2_000;

/**
 * How long a session waits from the server's first refusal before it fails with `remote_sessions_full`. Read at each
 * call, so tests can shorten it.
 */
function sessionWaitMs(): number {
  return Number(process.env.PIPKIN_REMOTE_SESSION_WAIT_MS ?? 30_000);
}

/**
 * A `sh` script that runs remote command line [command] on [remote] with `ssh` options [flags] (such as `-tt`) the way
 * [runSession] does, for a long-lived session another program runs, as a terminal or a background task is: over the
 * master, asking again each second while the server refuses it a session, and with a master of its own, as [sshArgs]
 * does, once there is none. Its exit status is `ssh`'s; [sessionWaitMs] after the first refusal, to the second, it
 * stops asking, writes `remote_sessions_full`'s message to stderr and exits with 255 ([sessionFailure]).
 *
 * It stops asking once process [owner], which starts it, is gone, as when Pi dies with a background task still
 * waiting: nothing would read the task, nor end it, as no remote watchdog runs yet to see its input close. The owner is
 * written in rather than read from `$PPID`, which would name whoever adopted the script had the owner died first.
 */
export function sessionScript(remote: Remote, command: string, options: { flags?: readonly string[]; owner?: number } = {}): string {
  const flags = options.flags ?? [];
  const ssh = quote(sshBinary(remote.paths));
  const check = words([...connectionArgs(remote), "-O", "check", destination(remote.host)]);
  const owned = `  kill -0 ${String(options.owner ?? process.pid)} 2>/dev/null || exit 255`;
  return [
    `c=${quote(command)}`,
    `f=${quote(join(socketsDir(remote.paths), "f"))}$$`,
    `full() { printf '%s\\n' ${quote(sessionsFull(remote.host.name).message)} >&2; exit 255; }`,
    // The deadline, in whole seconds since the epoch, is set at the first refusal.
    "d=",
    "while :; do",
    // One a killed script left behind under a reused pid would pass a session that ran for one that never did.
    '  rm -f -- "$f"',
    `  SHELL=${quote(shBinary(remote.paths))} ${FALLBACK_FILE}="$f" ${ssh} ${words([...flags, ...masterOptions(remote)])} -- "$c"`,
    "  code=$?",
    '  [ -e "$f" ] || exit "$code"',
    '  rm -f -- "$f"',
    owned,
    `  ${ssh} ${check} 2>/dev/null || exec ${ssh} ${words([...flags, ...directOptions(remote, false)])} -- "$c"`,
    `  [ -n "$d" ] || d=$(($(date +%s) + ${String(Math.ceil(sessionWaitMs() / 1000))}))`,
    '  [ "$(date +%s)" -lt "$d" ] || full',
    "  sleep 1",
    owned,
    '  [ "$(date +%s)" -lt "$d" ] || full',
    "done",
  ].join("\n");
}

/** `remote_sessions_full` when a [sessionScript] ended with exit [code] and [stderr] for having waited too long. */
export function sessionFailure(remote: Remote, code: number | null, stderr: string): HostError | undefined {
  const full = sessionsFull(remote.host.name);
  return code === 255 && stderr.includes(full.message) ? full : undefined;
}

/** Whether [remote] has a live master connection (`ssh -O check`). */
export async function isConnected(remote: Remote): Promise<boolean> {
  const { promise, resolve } = Promise.withResolvers<boolean>();
  execFile(sshBinary(remote.paths), [...connectionArgs(remote), "-O", "check", destination(remote.host)], { timeout: 10_000, env: sshEnvironment() }, (error) => {
    resolve(error === null);
  });
  return promise;
}

/** Ends [remote]'s master connection (`ssh -O exit`); nothing happens when there is none. */
export async function disconnect(remote: Remote): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  execFile(sshBinary(remote.paths), [...connectionArgs(remote), "-O", "exit", destination(remote.host)], { timeout: 10_000, env: sshEnvironment() }, () => {
    resolve();
  });
  return promise;
}

/**
 * Removes [remote]'s entries from Pipkin's `known_hosts` (`ssh-keygen -R`), so the next connect asks about its key
 * again. The name `ssh` files the key under comes from `ssh -G`, which also resolves a `config` host's alias.
 * `~/.ssh/known_hosts` is never written, so a key the user accepted there stays.
 */
export async function forgetHostKey(remote: Remote): Promise<void> {
  let resolved: string;
  try {
    resolved = (await run(sshBinary(remote.paths), ["-F", sshConfigFile(remote.paths), "-G", destination(remote.host)], { env: sshEnvironment() })).stdout;
  } catch (error) {
    if (errorCode(error) === "ENOENT") throw sshMissing();
    throw error;
  }
  const options = new Map(resolved.split("\n").map((line) => [line.slice(0, line.indexOf(" ")), line.slice(line.indexOf(" ") + 1).trim()]));
  const alias = options.get("hostkeyalias");
  const hostname = options.get("hostname") ?? remote.host.address;
  const port = options.get("port") ?? "22";
  const name = alias !== undefined && alias !== "none" ? alias : port === "22" ? hostname : `[${hostname}]:${port}`;
  const file = knownHostsFile(remote.paths);
  // A file without the host, or no file yet, leaves nothing to forget.
  await run(join(remote.paths.bin, "ssh-keygen"), ["-R", name, "-f", file]).catch(() => undefined);
  await rm(`${file}.old`, { force: true });
}

/** The environment `ssh` runs in: Termux's, without the host's own variables. */
export function sshEnvironment(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return { ...withoutHostVariables(process.env), ...extra };
}

export interface RunOptions {
  /**
   * The remote command's standard input: written and closed, piped from a stream, or `"open"` to hold it open until the
   * command ends, which a [watched] script needs. Closed at once when absent.
   */
  stdin?: Buffer | "open";
  /** Gets standard output as it arrives; [RunResult.stdout] stays empty then. */
  onStdout?: (chunk: Buffer) => void;
  /** Gets standard error as it arrives, besides [RunResult.stderr]. */
  onStderr?: (chunk: Buffer) => void;
  /** Kills the local `ssh` when it aborts; the run then rejects with its reason. */
  signal?: AbortSignal | undefined;
  /** Kills the local `ssh` after this long; the run then rejects with a `TimeoutError`. */
  timeoutMs?: number;
  /**
   * For a call the user waits on ([runScript] only): after this long without a byte from the remote, checks the
   * connection, and fails with `remote_unreachable` when it's gone, ending its master so the next call connects
   * afresh. Keepalives would notice only after 45 s or more, and a fresh connect then waits out its own timeout too.
   * Usually [READ_IDLE_MS].
   */
  idleTimeoutMs?: number;
}

/**
 * The silence [RunOptions.idleTimeoutMs] allows calls the user waits on: past `ConnectTimeout` (15 s), so without a
 * master `ssh`'s own connect error comes first. Tests shorten it.
 */
export const READ_IDLE_MS = Number(process.env.PIPKIN_REMOTE_IDLE_MS ?? 20_000);
/** How long a check of a silent call's connection waits for the remote to run a no-op. */
const PROBE_TIMEOUT_MS = 5_000;

/**
 * Whether [remote]'s master connection still answers: a no-op run through it, outside the session slots, which a call
 * that went silent may be holding. A frozen server, a dead network or a gone master never answers it; a server that
 * refuses the session for being at its limit while the master stays up does.
 */
async function answers(remote: Remote): Promise<boolean> {
  try {
    const { exit, fellBack } = await overMaster(remote, "exit 0", { signal: AbortSignal.timeout(PROBE_TIMEOUT_MS) });
    return exit.code === 0 || (fellBack && (await isConnected(remote)));
  } catch {
    return false;
  }
}

let fallbackFiles = 0;

/**
 * Runs remote command line [command] once over [remote]'s master ([masterOptions]); [fellBack] says the master didn't
 * carry the session, so nothing ran on the remote.
 */
async function overMaster(remote: Remote, command: string, options: RunOptions): Promise<{ exit: RunResult; fellBack: boolean }> {
  fallbackFiles++;
  const file = join(socketsDir(remote.paths), `f${String(process.pid)}-${String(fallbackFiles)}`);
  const env = sshEnvironment({ SHELL: shBinary(remote.paths), [FALLBACK_FILE]: file });
  // One a killed process left behind under a reused pid would pass a session that ran for one that never did.
  await rm(file, { force: true });
  let exit: RunResult;
  try {
    exit = await spawnSsh(remote.paths, [...masterOptions(remote), "--", command], options, env);
  } catch (error) {
    await rm(file, { force: true });
    throw error;
  }
  return {
    exit,
    fellBack: await rm(file).then(
      () => true,
      () => false,
    ),
  };
}

/**
 * Runs remote command line [command] on [remote] over its master, asking again after a short wait while the server
 * refuses the master a session, as one past its `MaxSessions` does: nothing ran then, so the call is made again as it
 * was, its input in full, until [RunOptions.signal] aborts or, [sessionWaitMs] after the first refusal, failing with
 * `remote_sessions_full`. Without a master, makes one, as [sshArgs] does, or fails
 * as a password host does. A refused session never becomes a connection of its own, which a password host can't sign
 * in on.
 */
async function runSession(remote: Remote, command: string, options: RunOptions): Promise<RunResult> {
  let deadline: number | undefined;
  for (let wait = RETRY_FIRST_MS; ; wait = Math.min(wait * 2, RETRY_MAX_MS)) {
    const { exit, fellBack } = await overMaster(remote, command, options);
    if (!fellBack) return exit;
    if (!(await isConnected(remote))) return spawnSsh(remote.paths, sshArgs(remote, command), options);
    deadline ??= Date.now() + sessionWaitMs();
    if (Date.now() >= deadline) throw sessionsFull(remote.host.name);
    await sleep(Math.min(wait, deadline - Date.now()), undefined, { signal: options.signal }).catch(() => undefined);
    options.signal?.throwIfAborted();
    // Past the deadline no session starts, however soon one would be free.
    if (Date.now() >= deadline) throw sessionsFull(remote.host.name);
  }
}

/**
 * Watches a run for [RunOptions.idleTimeoutMs] of silence: [touch] on each byte, [stop] when done. Silence checks the
 * connection ([answers]); a live one, as with a slow command, waits again, and a gone one aborts [signal].
 */
function idleWatch(remote: Remote, ms: number | undefined): { signal: AbortSignal | undefined; touch: () => void; stop: () => void } {
  if (ms === undefined) return { signal: undefined, touch: () => undefined, stop: () => undefined };
  const gone = new AbortController();
  let timer: NodeJS.Timeout | undefined;
  let stopped = false;
  const arm = (): void => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      timer = undefined;
      void answers(remote).then((alive) => {
        if (stopped) return;
        if (alive) arm();
        else gone.abort(unreachable(remote.host.name, "Connection timed out"));
      });
    }, ms);
  };
  arm();
  return {
    signal: gone.signal,
    touch: () => {
      if (timer !== undefined) arm();
    },
    stop: () => {
      stopped = true;
      clearTimeout(timer);
    },
  };
}

export interface RunResult {
  /** The remote command's exit status; null when the local `ssh` was killed by a signal. */
  code: number | null;
  stdout: Buffer;
  /** Standard error, its last 64 KiB. */
  stderr: string;
}

const MAX_STDERR = 64 * 1024;

/** Runs `ssh` with [args] and [env], feeding and collecting its standard streams per [options]. */
export async function spawnSsh(paths: RemotePaths, args: string[], options: RunOptions, env = sshEnvironment()): Promise<RunResult> {
  const signal = options.signal;
  signal?.throwIfAborted();
  const child = spawn(sshBinary(paths), args, { stdio: ["pipe", "pipe", "pipe"], env });
  const stdout: Buffer[] = [];
  let stderr = "";
  const { promise, resolve, reject } = Promise.withResolvers<RunResult>();
  child.on("error", (error) => {
    reject(errorCode(error) === "ENOENT" ? sshMissing() : error);
  });
  child.stdout.on("data", (chunk: Buffer) => {
    if (options.onStdout === undefined) stdout.push(chunk);
    else options.onStdout(chunk);
  });
  child.stderr.on("data", (chunk: Buffer) => {
    stderr = (stderr + chunk.toString("utf8")).slice(-MAX_STDERR);
    options.onStderr?.(chunk);
  });
  // The remote command may end without reading all of its input.
  child.stdin.on("error", () => undefined);
  const input = options.stdin;
  if (input === undefined) child.stdin.end();
  else if (input !== "open") child.stdin.end(input);

  const kill = (): void => {
    child.kill("SIGTERM");
    // A multiplexed `ssh` hands its standard streams to the master, which keeps them open while it's stuck on a dead
    // connection; waiting for them to end would wait for the master. Once the client has exited, nothing more comes.
    child.once("exit", () => {
      child.stdout.destroy();
      child.stderr.destroy();
    });
  };
  signal?.addEventListener("abort", kill, { once: true });
  child.on("close", (code) => {
    signal?.removeEventListener("abort", kill);
    child.stdin.destroy();
    resolve({ code, stdout: Buffer.concat(stdout), stderr });
  });
  const exit = await promise;
  signal?.throwIfAborted();
  return exit;
}

/**
 * Runs bash [script] on [remote] through the command wrapper, sharing the host's master connection ([runSession]) and
 * one of its session slots. Rejects with the plan's `HostError` when `ssh` itself failed, the connection died under the run, or
 * the remote has no `bash`; any other exit status, 255 included, is the script's and comes back in the result.
 */
export async function runScript(remote: Remote, script: string, options: RunOptions = {}): Promise<RunResult> {
  const signals = [options.signal, options.timeoutMs === undefined ? undefined : AbortSignal.timeout(options.timeoutMs)];
  const live = signals.filter((signal) => signal !== undefined);
  const signal = live.length > 1 ? AbortSignal.any(live) : live[0];
  const release = await acquireSession(remote.host.id, signal);
  // Started once the session is ours: waiting for a slot is no silence of the connection.
  const idle = idleWatch(remote, options.idleTimeoutMs);
  const stdout: Buffer[] = [];
  let exit: RunResult;
  try {
    exit = await runSession(remote, remoteScript(script), {
      ...options,
      signal: signal === undefined ? idle.signal : idle.signal === undefined ? signal : AbortSignal.any([signal, idle.signal]),
      onStdout: (chunk) => {
        idle.touch();
        if (options.onStdout === undefined) stdout.push(chunk);
        else options.onStdout(chunk);
      },
      onStderr: (chunk) => {
        idle.touch();
        options.onStderr?.(chunk);
      },
    });
  } catch (error) {
    if (idle.signal?.aborted !== true || signal?.aborted === true) throw error;
    // The master is stuck on a connection that's gone; ending it lets the next call connect afresh.
    await disconnect(remote);
    throw idle.signal.reason;
  } finally {
    idle.stop();
    release();
  }
  if (options.onStdout === undefined) exit = { ...exit, stdout: Buffer.concat(stdout) };
  const context = { name: remote.host.name, auth: remote.host.auth, interactive: false };
  // A master that outlives the run means the 255 and the stderr were the script's own, as when it ran `ssh` itself.
  // Without one, the connection failed: `ssh` says how, or, for a multiplexed client whose master gave up on a silent
  // server (keepalives), says nothing, as the master that knew is gone. Asking the server again could reach it once it
  // has recovered, yet the run still lost its connection.
  if (exit.code === 255 && !(await isConnected(remote))) {
    throw classifySshFailure(exit.stderr, context) ?? unreachable(remote.host.name, "Connection lost");
  }
  if (isBashMissing(exit.code, exit.stderr)) throw bashMissing(remote.host.name);
  return exit;
}

/**
 * Runs [command] with `bash` in remote folder [cwd] under the watchdog ([watched]), so aborting, a timeout or a lost
 * connection ends it on the remote too.
 */
export function runWatched(remote: Remote, command: string, cwd: string, options: Omit<RunOptions, "stdin"> = {}): Promise<RunResult> {
  return runScript(remote, watched(command, cwd), { ...options, stdin: "open" });
}

/** How long [checkConnection] lets a fresh connection take: past `ConnectTimeout` (15 s), whose error says more. */
const CHECK_CONNECT_TIMEOUT_MS = 30_000;

/**
 * Checks that [remote] can be reached now, rejecting with the `HostError` a call would get when it can't. Through a live
 * master it runs a no-op ([answers]), so a frozen server or a dead network fails within seconds, though keepalives
 * would take 45 s; the master is left to them, so a check that only came back late ends nothing. Without a master it
 * connects as a call would, unless [connect] is false, for a host only the user can sign in to: trying again and again
 * would only fill the server's log with refused sign-ins.
 */
export async function checkConnection(remote: Remote, connect: boolean): Promise<void> {
  if (await isConnected(remote)) {
    if (await answers(remote)) return;
    // A master that ended meanwhile is no frozen server; without it, the check is a connect's.
    if (await isConnected(remote)) throw unreachable(remote.host.name, "Connection timed out");
  }
  if (!connect) throw notConnected(remote.host.name);
  try {
    await runScript(remote, "true", { timeoutMs: CHECK_CONNECT_TIMEOUT_MS });
  } catch (error) {
    if (error instanceof DOMException && error.name === "TimeoutError") throw unreachable(remote.host.name, "Connection timed out");
    throw error;
  }
}
