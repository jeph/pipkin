/**
 * The `background` tool's tasks, texts and display, apart from Pi's session wiring in `./index.ts`. A task is one shell
 * command in its own process group, with its output in a log file, so Pi reads nothing while it runs.
 */
import { spawn } from "node:child_process";
import { closeSync, mkdirSync, openSync, readSync, fstatSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { StringEnum } from "@earendil-works/pi-ai";
import { type Static, Type } from "typebox";

export const TOOL_NAME = "background";
export const TOOL_LABEL = "Background";

/** The channel topic: the extension sends the app how many tasks keep the chat's Pi needed; the app sends `stop`. */
export const TOPIC = "background";

/** The custom message a finished task's result arrives in. */
export const RESULT_TYPE = "pipkin-background-result";

/** Each running task is a process Android counts toward its limit of 32 across all apps. */
export const MAX_RUNNING = 4;

/** What a result shows of a task's output; the log file keeps all of it. */
const TAIL_LINES = 40;
const TAIL_BYTES = 4096;

export const TOOL_DESCRIPTION =
  "Run a shell command in the background. Actions: start (runs `command`, returns its id at once), list, stop (by `id`). When a task exits, its exit status and last output arrive as a new message, which wakes you if you are idle; the full output stays in the task's log file.";

export const PROMPT_SNIPPET = "Run servers, watchers and long commands in the background; results arrive as a message";

export const PROMPT_GUIDELINES: string[] = [
  "Use `background` for anything that should keep running after a command returns: servers, watchers, long builds, timed waits. After starting a task, don't poll or sleep waiting for it; its result arrives as a message.",
];

export const BackgroundParams = Type.Object({
  action: StringEnum(["start", "list", "stop"] as const),
  command: Type.Optional(Type.String({ description: "Shell command to run (start)" })),
  label: Type.Optional(Type.String({ description: "A few words naming the task for the user (start)" })),
  cwd: Type.Optional(Type.String({ description: "Working directory (start); defaults to the session's" })),
  timeout: Type.Optional(Type.Number({ description: "Seconds before the task is stopped (start); none by default" })),
  id: Type.Optional(Type.String({ description: "Task id (stop)" })),
});

export type BackgroundParams = Static<typeof BackgroundParams>;

export type TaskStatus = "running" | "exited" | "stopped" | "timed_out" | "failed";

export interface Task {
  id: string;
  label: string;
  command: string;
  cwd: string;
  /** The session that started it, which its result goes to. */
  sessionId: string;
  logPath: string;
  startedAt: number;
  status: TaskStatus;
  endedAt?: number;
  exitCode?: number | null;
  signal?: string | null;
  /** Why it failed to start, for status `failed`. */
  error?: string;
}

/** What a finished task's result message carries besides its text. */
export interface ResultDetails {
  tasks: {
    id: string;
    label: string;
    command: string;
    status: TaskStatus;
    exitCode: number | null;
    signal: string | null;
    durationMs: number;
    logPath: string;
  }[];
}

/** `45s`, `2m 13s`, `1h 4m`. */
export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** How a finished task ended, in a few words. */
export function outcome(task: Task): string {
  switch (task.status) {
    case "running":
      return "running";
    case "exited":
      return task.exitCode === null || task.exitCode === undefined
        ? `ended by ${task.signal ?? "a signal"}`
        : `exit ${task.exitCode}`;
    // Only the user's Stop sends a result for a stopped task: the agent's own stop already answered it.
    case "stopped":
      return "stopped by the user";
    case "timed_out":
      return "timed out";
    case "failed":
      return `failed to start: ${task.error ?? "unknown error"}`;
  }
}

/**
 * The last [TAIL_LINES] lines of [path] within its last [TAIL_BYTES] bytes, without terminal escapes or carriage-return
 * progress redraws; empty when the file is empty or gone.
 */
export function readTail(path: string): string {
  let fd: number;
  try {
    fd = openSync(path, "r");
  } catch {
    return "";
  }
  try {
    const size = fstatSync(fd).size;
    const length = Math.min(size, TAIL_BYTES);
    const buffer = Buffer.alloc(length);
    readSync(fd, buffer, 0, length, size - length);
    // oxlint-disable-next-line no-control-regex -- terminal escapes are what this strips.
    const text = buffer.toString("utf8").replace(/\u001b\[[0-?]*[ -/]*[@-~]|\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)?/g, "");
    const lines = text.split("\n").map((line) => line.slice(line.lastIndexOf("\r") + 1));
    // A cut first line is only part of one.
    if (length < size) lines.shift();
    while (lines.length > 0 && lines.at(-1)?.trim() === "") lines.pop();
    return lines.slice(-TAIL_LINES).join("\n");
  } finally {
    closeSync(fd);
  }
}

/** The text of one result message, covering every task that finished since the last one. */
export function resultText(tasks: Task[]): string {
  return tasks
    .map((task) => {
      const name = `Background task ${task.id} (${task.label})`;
      if (task.status === "failed") return `${name} ${outcome(task)}.`;
      const head = `${name} ${outcome(task)} after ${formatDuration((task.endedAt ?? task.startedAt) - task.startedAt)}.`;
      const tail = readTail(task.logPath);
      const output = tail === "" ? "It printed nothing." : `Last output:\n\`\`\`\n${tail}\n\`\`\``;
      return `${head}\n${output}\nFull output: ${task.logPath}`;
    })
    .join("\n\n");
}

export function resultDetails(tasks: Task[]): ResultDetails {
  return {
    tasks: tasks.map((task) => ({
      id: task.id,
      label: task.label,
      command: task.command,
      status: task.status,
      exitCode: task.exitCode ?? null,
      signal: task.signal ?? null,
      durationMs: (task.endedAt ?? task.startedAt) - task.startedAt,
      logPath: task.logPath,
    })),
  };
}

/** The `list` action's text. */
export function listText(tasks: Task[], now: number): string {
  if (tasks.length === 0) return "No background tasks.";
  return tasks
    .map((task) => {
      const time = formatDuration((task.endedAt ?? now) - task.startedAt);
      return `${task.id} ${task.label}: ${outcome(task)} (${time})\n  $ ${task.command}\n  log: ${task.logPath}`;
    })
    .join("\n");
}

/** The theme colors the widget uses, as Pi's `Theme.fg` takes them. */
export type WidgetColor = "accent" | "dim" | "muted" | "success" | "error" | "warning" | "text";

export interface WidgetTheme {
  fg(color: WidgetColor, text: string): string;
}

/** A time of day as `14:02`. */
export function clockTime(at: number): string {
  const date = new Date(at);
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

/**
 * The widget's lines: a heading with how many tasks run, then each task with its state, label and command. A running
 * task shows when it started rather than for how long, so its line stays put and the tab marks only real changes. Null
 * when there's no task to show.
 */
export function widgetLines(tasks: Task[], theme: WidgetTheme): string[] | null {
  if (tasks.length === 0) return null;
  const running = tasks.filter((task) => task.status === "running").length;
  const heading = running > 0 ? theme.fg("accent", `● Background (${running} running)`) : theme.fg("dim", "○ Background");
  const lines = [heading];
  tasks.forEach((task, index) => {
    const last = index === tasks.length - 1;
    lines.push(`${theme.fg("dim", last ? "└─" : "├─")} ${taskLine(task, theme)}`);
    lines.push(`${theme.fg("dim", last ? "  " : "│ ")}   ${theme.fg("muted", `$ ${oneLine(task.command)}`)}`);
  });
  return lines;
}

function taskLine(task: Task, theme: WidgetTheme): string {
  const label = oneLine(task.label);
  if (task.status === "running") {
    return `${theme.fg("warning", "◐")} ${theme.fg("accent", label)} ${theme.fg("dim", `· since ${clockTime(task.startedAt)}`)}`;
  }
  const glyph = task.status === "exited" && task.exitCode === 0 ? theme.fg("success", "✓") : theme.fg("error", "✗");
  const time = formatDuration((task.endedAt ?? task.startedAt) - task.startedAt);
  return `${glyph} ${theme.fg("text", label)} ${theme.fg("dim", `· ${outcome(task)} after ${time}`)}`;
}

/** Text on one line, without terminal controls, for the widget. */
function oneLine(value: string): string {
  // oxlint-disable-next-line no-control-regex -- control characters are what this removes.
  return value.replace(/\s+/g, " ").replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim();
}

/** A label for a task started without one: the command's first line, cut to a tab's width. */
export function defaultLabel(command: string): string {
  const first = oneLine(command.split("\n")[0] ?? command);
  return first.length > 40 ? `${first.slice(0, 39)}…` : first;
}

/** Where tasks' output goes. Pi processes share it, so each log's name starts with its Pi's pid. */
export function logDir(): string {
  const dir = join(tmpdir(), "pipkin-background");
  mkdirSync(dir, { recursive: true });
  return dir;
}

export interface Started {
  /** The process and its group, or undefined when it couldn't start, which [ended] then rejects with. */
  pid: number | undefined;
  /** Resolves with how the process ended. */
  ended: Promise<{ code: number | null; signal: string | null }>;
}

/**
 * Runs [command] with [shell] in its own process group, writing stdout and stderr to [logPath]. A shell that takes the
 * command on stdin (`commandTransport: "stdin"`) gets it there. Throws when the process can't start.
 */
export function startProcess(
  shell: { shell: string; args: string[]; commandTransport?: "argv" | "stdin" },
  command: string,
  cwd: string,
  logPath: string,
): Started {
  const log = openSync(logPath, "w");
  try {
    const fromStdin = shell.commandTransport === "stdin";
    const child = spawn(shell.shell, fromStdin ? shell.args : [...shell.args, command], {
      cwd,
      detached: true,
      stdio: [fromStdin ? "pipe" : "ignore", log, log],
    });
    if (fromStdin) {
      child.stdin?.on("error", () => undefined);
      child.stdin?.end(command);
    }
    const ended = new Promise<{ code: number | null; signal: string | null }>((resolve, reject) => {
      child.once("error", reject);
      child.once("exit", (code, signal) => resolve({ code, signal }));
    });
    return { pid: child.pid, ended };
  } finally {
    closeSync(log);
  }
}

/** Signals [pid]'s whole process group; false when there's none. */
export function signalGroup(pid: number | undefined, signal: NodeJS.Signals): boolean {
  // process.kill(-pid) with no pid, or one of 0 or below, would signal this process's group or every process.
  if (pid === undefined || pid <= 0) return false;
  try {
    process.kill(-pid, signal);
    return true;
  } catch {
    return false;
  }
}
