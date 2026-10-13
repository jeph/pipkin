import { execFile, spawn } from "node:child_process";
import { readdir, readFile, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { promisify } from "node:util";
import { HostError } from "../env.js";
import type { ModeContext } from "../mode.js";
import { positiveIntParam, stringParam } from "../params.js";

/**
 * Runs in the shell `script` starts on its new pseudo-terminal: sizes the terminal, then records its device path so
 * later resizes can reach it (after the first size, so none of them is overwritten), and replaces itself with
 * Termux's `login`, which starts the user's login shell the way a Termux session does.
 */
const SHELL_COMMAND =
  'stty rows "$PIPKIN_ROWS" cols "$PIPKIN_COLS" && tty > "$PIPKIN_TTY" && unset PIPKIN_ROWS PIPKIN_COLS PIPKIN_TTY && exec login';
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
  return { pid, ppid, session };
}

/**
 * Whether anything besides the shell runs in the session `script`'s child leads: foreground commands, background jobs,
 * a `wait`ed job and whatever they start. A shell builtin looping on its own, or a program that started a session of
 * its own, doesn't count.
 */
export function sessionHasWork(processes: ProcStat[], scriptPid: number): boolean {
  const leader = processes.find((it) => it.ppid === scriptPid && it.session === it.pid);
  if (leader === undefined) return false;
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
    void listProcesses()
      .then((processes) => {
        streak = sessionHasWork(processes, scriptPid) === busy ? 0 : streak + 1;
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
 */
export async function terminal(ctx: ModeContext): Promise<void> {
  const cwd = stringParam(ctx.params, "cwd");
  const rows = positiveIntParam(ctx.params, "rows");
  const cols = positiveIntParam(ctx.params, "cols");
  const isDirectory = await stat(cwd).then(
    (stats) => stats.isDirectory(),
    () => false,
  );
  if (!isDirectory) throw new HostError("cwd_missing", `Folder not found: ${cwd}`);

  const deviceFile = join(ctx.env.root, `terminal-tty-${process.pid}`);
  const { PIPKIN_TOKEN: _token, PIPKIN_ROOT: _root, PI_CODING_AGENT_DIR: _configDir, ...base } = process.env;
  const child = spawn(join(ctx.env.prefix, "bin", "script"), ["-qfec", SHELL_COMMAND, "/dev/null"], {
    cwd,
    env: {
      ...base,
      TERM: "xterm-256color",
      COLORTERM: "truecolor",
      PIPKIN_ROWS: String(rows),
      PIPKIN_COLS: String(cols),
      PIPKIN_TTY: deviceFile,
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
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
    child.pid === undefined ? () => undefined : watchBusy(child.pid, (busy) => conn.send({ type: "busy", busy }));

  let reported = false;
  const report = (code: number | null, signal: string | null): void => {
    if (reported) return;
    reported = true;
    stopWatching();
    void rm(deviceFile, { force: true }).finally(() => {
      conn.send({ type: "pipkin_exit", code, signal, stderr });
      conn.finish();
    });
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
