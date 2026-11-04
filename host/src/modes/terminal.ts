import { execFile, spawn } from "node:child_process";
import { readdir, readFile, rm, stat } from "node:fs/promises";
import { basename, join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { promisify } from "node:util";
import { folderNotFound, type HostError, withoutHostVariables } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { positiveIntParam, stringParam } from "../params.js";
import { classifySshFailure, errorJson, sshMissing, unreachable } from "../remote/errors.js";
import { quote, remoteScript } from "../remote/script.js";
import { isConnected, type Remote, sessionFailure, sessionScript, sshInstalled } from "../remote/ssh.js";
import { remoteFolder } from "./remote-folder.js";

/**
 * Runs in the shell `script` starts on its new pseudo-terminal: sizes the terminal, then records its device path so
 * later resizes can reach it (after the first size, so none of them is overwritten), and replaces itself with
 * Termux's `login`, which starts the user's login shell the way a Termux session does.
 */
const SHELL_COMMAND =
  'stty rows "$PIPKIN_ROWS" cols "$PIPKIN_COLS" && tty > "$PIPKIN_TTY" && unset PIPKIN_ROWS PIPKIN_COLS PIPKIN_TTY && exec login';
/**
 * [SHELL_COMMAND] for a remote workspace: the same sizing and recording, then `ssh -tt` running the remote user's login
 * shell in remote folder [path] ([sessionScript], which waits while the server refuses the master a session), so
 * resizes reach it as `ssh` forwards the window size. `ssh` never prompts here, as in every background call; its own
 * messages go to the file named by `PIPKIN_SSH_ERRORS`, so a failed connection can be told from the shell's output.
 * The remote shell's own errors come through the terminal, as `-tt` merges them there. Run by `sh`, whose quoting
 * [quote] writes.
 */
export function remoteShellCommand(remote: Remote, path: string): string {
  const session = sessionScript(remote, remoteScript(`cd -- ${quote(path)} && exec "$SHELL" -l`), { flags: ["-tt"] });
  return [
    'stty rows "$PIPKIN_ROWS" cols "$PIPKIN_COLS"',
    'tty > "$PIPKIN_TTY"',
    'exec 2>"$PIPKIN_SSH_ERRORS"',
    "unset PIPKIN_ROWS PIPKIN_COLS PIPKIN_TTY PIPKIN_SSH_ERRORS",
    `{\n${session}\n}`,
  ].join(" && ");
}

/**
 * Why a remote terminal's `ssh` ended, from its exit [code] and what it wrote to [errors], including a server that had
 * no free session for it; undefined when the remote shell ended it instead, including with a 255 of its own while the
 * connection stays up. A 255 with no connection left and nothing from `ssh` is a connection lost under the shell, as
 * [runScript] reports one: a multiplexed client whose master gave up on a silent server says nothing.
 */
async function remoteFailure(remote: Remote, code: number | null, errors: string): Promise<HostError | undefined> {
  if (code !== 255) return undefined;
  const full = sessionFailure(remote, code, errors);
  if (full !== undefined) return full;
  if (await isConnected(remote)) return undefined;
  return classifySshFailure(errors, { name: remote.host.name, auth: remote.host.auth, interactive: false }) ?? unreachable(remote.host.name, "Connection lost");
}

/** Output the host holds while Pipkin is not reading; above it the shell waits until Pipkin reads again. */
const OUTPUT_BACKLOG_LIMIT = 4 * 1024 * 1024;
const STDERR_TAIL = 16 * 1024;
/** How long a resize waits for the shell to record its terminal, which it does as it starts. */
const DEVICE_WAIT_MS = 50;
const DEVICE_ATTEMPTS = 40;
/** How often the host looks for work in the shell's session. */
const BUSY_POLL_MS = 1000;
/**
 * Polls in a row that must find work before the host reports the shell busy, so quick children such as a prompt's
 * hooks or the shell's own startup don't count.
 */
const BUSY_POLLS = 2;
/**
 * Polls in a row that must find no work before the host reports the shell idle, so a poll landing between two
 * commands of one command line doesn't let Pipkin's keep-alive go, which it may not get back from the background.
 */
const IDLE_POLLS = 3;

const execFileAsync = promisify(execFile);

function isPositiveInt(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

/** The fields of `/proc/<pid>/stat` the busy check uses. */
export interface ProcStat {
  pid: number;
  /** The file name of the program the process runs, cut to the kernel's 15 characters. */
  comm: string;
  ppid: number;
  session: number;
}

/**
 * Parses `/proc/<pid>/stat`. The command name sits in parentheses and may itself hold spaces and parentheses, so the
 * fields are read after its last `)`: state, ppid, process group, then session.
 */
export function parseProcStat(text: string): ProcStat | undefined {
  const open = text.indexOf("(");
  const close = text.lastIndexOf(")");
  if (open < 0 || close < open) return undefined;
  const pid = Number(text.slice(0, open).trim());
  const fields = text.slice(close + 1).trim().split(/\s+/);
  const ppid = Number(fields[1]);
  const session = Number(fields[3]);
  if (![pid, ppid, session].every(Number.isInteger)) return undefined;
  return { pid, comm: text.slice(open + 1, close), ppid, session };
}

/** The kernel keeps this many characters of a program's file name as a process's `comm`. */
const COMM_LENGTH = 15;

/**
 * Shells that may lead the session while it waits for commands, besides the login shell: `sh` running `login` as the
 * session starts, and shells an rc file or the user `exec`s in place of the login shell.
 */
const SHELLS: Record<string, true> = {
  sh: true,
  login: true,
  ash: true,
  bash: true,
  dash: true,
  zsh: true,
  fish: true,
  ksh: true,
  mksh: true,
  oksh: true,
  loksh: true,
  yash: true,
};

/** The `comm` of the login shell Termux's `login` started with the environment [environ] (`/proc/<pid>/environ`). */
export function loginShellComm(environ: string): string | undefined {
  const shell = environ
    .split("\0")
    .find((it) => it.startsWith("SHELL="))
    ?.slice("SHELL=".length);
  return shell ? basename(shell).slice(0, COMM_LENGTH) : undefined;
}

/** The process that leads the session `script`'s child started: the shell once it has started. */
export function sessionLeader(processes: ProcStat[], scriptPid: number): ProcStat | undefined {
  return processes.find((it) => it.ppid === scriptPid && it.session === it.pid);
}

/**
 * Whether anything besides the shell runs in the session [leader] leads: foreground commands, background jobs, a
 * `wait`ed job and whatever they start, or a program the user `exec`ed, which replaces the shell in the leader's own
 * process, so the leader no longer runs a shell or [loginShell] (the `comm` of the user's login shell). A shell builtin
 * looping on its own, or a program that started a session of its own, doesn't count.
 */
export function sessionHasWork(processes: ProcStat[], leader: ProcStat, loginShell: string | undefined): boolean {
  if (leader.comm !== loginShell && SHELLS[leader.comm] !== true) return true;
  return processes.some((it) => it.session === leader.session && it.pid !== leader.pid);
}

/** This user's processes; Android hides other users' from `/proc`. */
async function listProcesses(): Promise<ProcStat[]> {
  const pids = (await readdir("/proc")).filter((name) => /^\d+$/.test(name));
  const stats = await Promise.all(
    pids.map((pid) => readFile(`/proc/${pid}/stat`, "utf8").then(parseProcStat, () => undefined)),
  );
  return stats.filter((it) => it !== undefined);
}

/**
 * Reports through [onChange] whether the shell under `script` ([scriptPid]) has work running, polling `/proc` until
 * the returned function stops it. The shell starts idle.
 */
function watchBusy(scriptPid: number, onChange: (busy: boolean) => void): () => void {
  let busy = false;
  // Polls in a row that disagree with `busy`.
  let streak = 0;
  let polling = false;
  const timer = setInterval(() => {
    if (polling) return;
    polling = true;
    void hasWork(scriptPid)
      .then((work) => {
        streak = work === busy ? 0 : streak + 1;
        if (streak < (busy ? IDLE_POLLS : BUSY_POLLS)) return;
        busy = !busy;
        streak = 0;
        onChange(busy);
      })
      .catch(() => undefined)
      .finally(() => {
        polling = false;
      });
  }, BUSY_POLL_MS);
  return () => clearInterval(timer);
}

/** One poll of [watchBusy]: whether the shell under `script` ([scriptPid]) has work running now. */
async function hasWork(scriptPid: number): Promise<boolean> {
  const processes = await listProcesses();
  const leader = sessionLeader(processes, scriptPid);
  if (leader === undefined) return false;
  const environ = await readFile(`/proc/${leader.pid}/environ`, "utf8").catch(() => "");
  return sessionHasWork(processes, leader, loginShellComm(environ));
}

/**
 * Applies the newest requested size to the shell's terminal with `stty -F`, one at a time; the kernel then signals
 * the programs in it to redraw. `script` cannot do this itself: it only follows the size of a terminal it runs in,
 * and the host gives it pipes.
 */
class Resizer {
  private pending: { rows: number; cols: number } | undefined;
  private running = false;
  private device: string | undefined;

  constructor(
    private readonly stty: string,
    private readonly deviceFile: string,
  ) {}

  resize(rows: number, cols: number): void {
    this.pending = { rows, cols };
    if (!this.running) void this.drain();
  }

  private async drain(): Promise<void> {
    this.running = true;
    try {
      while (this.pending !== undefined) {
        const { rows, cols } = this.pending;
        this.pending = undefined;
        const device = await this.recordedDevice();
        if (device === undefined) return;
        await execFileAsync(this.stty, ["-F", device, "rows", String(rows), "cols", String(cols)]).catch(
          (error: unknown) => {
            process.stderr.write(`pipkin-host terminal: resize failed: ${String(error)}\n`);
          },
        );
      }
    } finally {
      this.running = false;
    }
  }

  private async recordedDevice(): Promise<string | undefined> {
    for (let attempt = 0; this.device === undefined && attempt < DEVICE_ATTEMPTS; attempt++) {
      const recorded = (await readFile(this.deviceFile, "utf8").catch(() => "")).trim();
      if (recorded.startsWith("/dev/")) this.device = recorded;
      else await sleep(DEVICE_WAIT_MS);
    }
    return this.device;
  }
}

/**
 * Runs the user's login shell on a pseudo-terminal in `cwd`, through util-linux's `script` from Termux's bootstrap.
 * After the request the connection stays JSONL both ways: Pipkin sends `input` (base64 bytes) and `resize`
 * (`rows`, `cols`); the host sends `output` (base64 bytes), `busy` (`busy`) whenever work starts or stops running in
 * the shell, and, once the shell has gone, one `pipkin_exit`. Closing the connection ends the shell.
 *
 * A remote workspace's stand-in as `cwd` runs the remote user's login shell in the remote folder over `ssh` instead.
 * Its busy flag stays idle, as the remote's processes can't be seen from here. When the connection fails, the
 * terminal shows why on a line of its own, and `pipkin_exit` carries it as `error` (`{code, message, i18n}`).
 */
export async function terminal(ctx: ModeContext): Promise<void> {
  const cwd = stringParam(ctx.params, "cwd");
  const rows = positiveIntParam(ctx.params, "rows");
  const cols = positiveIntParam(ctx.params, "cols");
  const isDirectory = await stat(cwd).then(
    (stats) => stats.isDirectory(),
    () => false,
  );
  if (!isDirectory) throw folderNotFound("cwd_missing", cwd);
  const folder = await remoteFolder(ctx.env, cwd);
  if (folder !== null && !(await sshInstalled(folder.remote.paths))) throw sshMissing();

  const deviceFile = join(ctx.env.root, `terminal-tty-${process.pid}`);
  const errorsFile = join(ctx.env.root, `terminal-ssh-${process.pid}`);
  const base = withoutHostVariables(process.env);
  const env: NodeJS.ProcessEnv = {
    ...base,
    TERM: "xterm-256color",
    COLORTERM: "truecolor",
    PIPKIN_ROWS: String(rows),
    PIPKIN_COLS: String(cols),
    PIPKIN_TTY: deviceFile,
  };
  // `script` runs its command with `$SHELL`, which may be fish; the remote command is quoted for `sh`.
  if (folder !== null) Object.assign(env, { SHELL: join(ctx.env.prefix, "bin", "sh"), PIPKIN_SSH_ERRORS: errorsFile });
  const command = folder === null ? SHELL_COMMAND : remoteShellCommand(folder.remote, folder.path);
  const child = spawn(join(ctx.env.prefix, "bin", "script"), ["-qfec", command, "/dev/null"], { cwd, env, stdio: ["pipe", "pipe", "pipe"] });
  const { conn } = ctx;
  const { socket } = conn;

  let stderr = "";
  child.stderr.on("data", (chunk: Buffer) => {
    stderr = (stderr + chunk.toString("utf8")).slice(-STDERR_TAIL);
  });
  child.stdin.on("error", () => undefined);
  child.stdout.on("data", (chunk: Buffer) => {
    conn.send({ type: "output", data: chunk.toString("base64") });
    if (socket.writableLength >= OUTPUT_BACKLOG_LIMIT) child.stdout.pause();
  });
  socket.on("drain", () => child.stdout.resume());
  socket.on("close", () => child.stdout.resume());

  const resizer = new Resizer(join(ctx.env.prefix, "bin", "stty"), deviceFile);
  conn.listen((message) => {
    if (message.type === "input" && typeof message.data === "string") {
      child.stdin.write(Buffer.from(message.data, "base64"));
    } else if (message.type === "resize" && isPositiveInt(message.rows) && isPositiveInt(message.cols)) {
      resizer.resize(message.rows, message.cols);
    }
  });

  const stopWatching =
    child.pid === undefined || folder !== null ? () => undefined : watchBusy(child.pid, (busy) => conn.send({ type: "busy", busy }));

  /** For a remote terminal whose connection failed: says why in the terminal, and gives the error for the exit line. */
  const connectionFailure = async (code: number | null): Promise<JsonObject> => {
    if (folder === null) return {};
    const errors = await readFile(errorsFile, "utf8").catch(() => "");
    await rm(errorsFile, { force: true });
    const failure = await remoteFailure(folder.remote, code, errors);
    if (failure === undefined) return {};
    conn.send({ type: "output", data: Buffer.from(`\r\n${failure.message}\r\n`).toString("base64") });
    return { error: errorJson(failure) };
  };

  let reported = false;
  const report = (code: number | null, signal: string | null): void => {
    if (reported) return;
    reported = true;
    stopWatching();
    void (async () => {
      const failure = await connectionFailure(code).catch(() => ({}));
      await rm(deviceFile, { force: true }).catch(() => undefined);
      conn.send({ type: "pipkin_exit", code, signal, stderr, ...failure });
      conn.finish();
    })();
  };
  // `close` fires only after stdout ended, so the exit line follows all of the shell's output.
  child.on("close", report);
  child.on("error", (error) => {
    stderr += `\n${error.message}\n`;
    report(null, null);
  });

  // `script` would only pass SIGTERM on to the shell, which ignores it. Killed, it leaves the terminal with no
  // controlling end, so the kernel hangs up the shell, which hangs up its jobs.
  ctx.signal.addEventListener("abort", () => child.kill("SIGKILL"), { once: true });
}
