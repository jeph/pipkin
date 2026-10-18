/**
 * What Pipkin keeps on disk about each background task, so that a later Pi can tell the agent what became of tasks
 * whose Pi stopped without passing on their results, as when Android ends Termux. A record holds only what its Pi saw:
 * that the task started, a stop it began and the exit it got. A later Pi adds what it checks itself, that the task's
 * process group still ran and was stopped; whatever no record shows is reported as unknown.
 */
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { StringEnum } from "@earendil-works/pi-ai";
import { type Static, Type } from "typebox";
import { Value } from "typebox/value";
import { isObject } from "../../framing.js";
import { clockTime, formatDuration, outcome, outputText, TaskStatus, taskDir } from "./tasks.js";

/** Why Pipkin began stopping a task: the agent's `stop`, the user's Stop, its timeout, its session being left or its Pi exiting. */
const StopReason = StringEnum(["agent", "user", "timeout", "quit", "switch", "exit"] as const);
export type StopReason = Static<typeof StopReason>;

const TaskRecord = Type.Object({
  /** Unique across Pi processes, unlike the `bgN` id, which each Pi counts from 1. */
  key: Type.String(),
  id: Type.String(),
  label: Type.String(),
  command: Type.String(),
  sessionId: Type.String(),
  logPath: Type.String(),
  startedAt: Type.Number(),
  /** The task's process, which leads its own process group. */
  pid: Type.Number(),
  /** Tells that process apart from a later one given the same id; null when it couldn't be read. */
  identity: Type.Union([Type.String(), Type.Null()]),
  /** The Pi that ran the task. */
  owner: Type.Object({ pid: Type.Number(), identity: Type.Union([Type.String(), Type.Null()]) }),
  /** The boot the processes ran in: a restart of the phone ends them all and starts numbering processes anew. */
  boot: Type.String(),
  stop: Type.Optional(Type.Object({ reason: StopReason, at: Type.Number() })),
  exit: Type.Optional(
    Type.Object({
      status: TaskStatus,
      exitCode: Type.Union([Type.Number(), Type.Null()]),
      signal: Type.Union([Type.String(), Type.Null()]),
      error: Type.Optional(Type.String()),
      at: Type.Number(),
    }),
  ),
  /** A later Pi found the task's group running with no Pi tracking it and stopped it; `stopped` is false if it stayed. */
  orphan: Type.Optional(Type.Object({ at: Type.Number(), stopped: Type.Boolean() })),
});
export type TaskRecord = Static<typeof TaskRecord>;

/** Records older than this whose Pi is gone are deleted, with logs no record names. */
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

/** How long an orphaned group gets after SIGTERM before SIGKILL, and after SIGKILL before Pipkin gives up on it. */
const TERM_WAIT_MS = 3000;
const KILL_WAIT_MS = 1000;
const POLL_MS = 100;

const RECORD_SUFFIX = ".json";

/** Whether processes can be read from `/proc`: on Linux and on Android, where Node's platform is `android`, not `linux`. */
const PROCFS = existsSync("/proc/self/stat");

let cachedBoot: string | undefined;

/** The current boot's id: the kernel's `boot_id`, or the boot time without `/proc`; empty when neither can be read. */
export function bootId(): string {
  cachedBoot ??= readBoot();
  return cachedBoot;
}

function readBoot(): string {
  try {
    if (PROCFS) return readFileSync("/proc/sys/kernel/random/boot_id", "utf8").trim();
    return execFileSync("sysctl", ["-n", "kern.boottime"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return "";
  }
}

/**
 * What tells process [pid] apart from an earlier or later process with the same id: when it started, from `/proc`, or
 * from `ps` without it. Undefined when there's no such process; throws when it can't tell, so a process that couldn't
 * be read is never taken for one that's gone.
 */
export function processIdentity(pid: number): string | undefined {
  if (PROCFS) {
    let stat: string;
    try {
      stat = readFileSync(`/proc/${pid}/stat`, "utf8");
    } catch (error) {
      if (isErrno(error, "ENOENT") || isErrno(error, "ESRCH")) return undefined;
      throw error;
    }
    // The command name in parentheses can hold spaces; the fields after it start at the 3rd, so starttime, the 22nd,
    // is the 20th of them.
    const started = stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19];
    if (started === undefined) throw new Error(`/proc/${pid}/stat has no start time`);
    return started;
  }
  try {
    const started = execFileSync("ps", ["-o", "lstart=", "-p", String(pid)], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    return started === "" ? undefined : started;
  } catch (error) {
    // ps exits with 1 and prints nothing when no process has the id.
    if (isObject(error) && error.status === 1 && error.stdout === "") return undefined;
    throw error;
  }
}

/** [pid]'s identity for a new record: null when it can't be read, which later checks take as unknown. */
export function recordedIdentity(pid: number): string | null {
  try {
    return processIdentity(pid) ?? null;
  } catch {
    return null;
  }
}

function isErrno(error: unknown, code: string): boolean {
  return isObject(error) && error.code === code;
}

function recordPath(key: string): string {
  return join(taskDir(), `${key}${RECORD_SUFFIX}`);
}

/** Writes [record] whole or not at all: a Pi killed mid-write leaves the previous version. */
export function saveRecord(record: TaskRecord): void {
  const path = recordPath(record.key);
  const temporary = `${path}.${process.pid}.tmp`;
  try {
    writeFileSync(temporary, JSON.stringify(record));
    renameSync(temporary, path);
  } catch {
    // Without its record a lost task goes unreported, which says nothing false.
    rmSync(temporary, { force: true });
  }
}

export function removeRecord(key: string): void {
  rmSync(recordPath(key), { force: true });
}

export function readRecords(): TaskRecord[] {
  let names: string[];
  try {
    names = readdirSync(taskDir());
  } catch {
    return [];
  }
  const records: TaskRecord[] = [];
  for (const name of names) {
    if (!name.endsWith(RECORD_SUFFIX)) continue;
    try {
      const record: unknown = JSON.parse(readFileSync(join(taskDir(), name), "utf8"));
      if (Value.Check(TaskRecord, record)) records.push(record);
    } catch {
      // Deleted while being read, or not a record.
    }
  }
  return records;
}

/** This Pi process, as records name their owner. */
export interface Self {
  pid: number;
  identity: string | null;
  /** Whether this Pi still tracks the task with [key]. */
  tracks(key: string): boolean;
}

/**
 * Who tracks [record]'s task: this Pi, another Pi that's still running, or no one. An owner that couldn't be
 * identified, then or now, counts as running while any process has its id, so its task is never reported lost on a
 * guess.
 */
export function tracker(record: TaskRecord, self: Self): "self" | "other" | "none" {
  if (record.owner.pid === self.pid && record.owner.identity === self.identity) {
    return self.tracks(record.key) ? "self" : "none";
  }
  if (record.boot !== bootId()) return "none";
  if (record.owner.identity === null) return processExists(record.owner.pid) ? "other" : "none";
  try {
    return processIdentity(record.owner.pid) === record.owner.identity ? "other" : "none";
  } catch {
    return processExists(record.owner.pid) ? "other" : "none";
  }
}

/**
 * Whether [record]'s task group still runs, known only while its leader can be read: true when the leader is the
 * task's process, false when no group has its id or the leader is another process, undefined otherwise. Once the
 * leader is gone, a group with its id may be the task's or a later one whose own leader exited too, so it's left alone.
 */
export function groupRunning(record: TaskRecord): boolean | undefined {
  if (record.boot !== bootId()) return false;
  if (!groupExists(record.pid)) return false;
  if (record.identity === null) return undefined;
  let leader: string | undefined;
  try {
    leader = processIdentity(record.pid);
  } catch {
    return undefined;
  }
  if (leader === undefined) return undefined;
  return leader === record.identity;
}

/**
 * Ends [record]'s group with SIGTERM, then SIGKILL; true once nothing in it runs. The group is checked to be the
 * task's first; after that, a group with its id is still the task's, as the id stays taken while any of it runs, so the
 * leader exiting on SIGTERM doesn't spare the rest.
 */
export async function stopGroup(record: TaskRecord): Promise<boolean> {
  if (groupRunning(record) !== true) return groupRunning(record) === false;
  for (const [signal, waitMs] of [
    ["SIGTERM", TERM_WAIT_MS],
    ["SIGKILL", KILL_WAIT_MS],
  ] as const) {
    if (!groupExists(record.pid)) return true;
    try {
      process.kill(-record.pid, signal);
    } catch {
      // The group ended since the check.
    }
    for (let waited = 0; waited < waitMs && groupExists(record.pid); waited += POLL_MS) await sleep(POLL_MS);
  }
  return !groupExists(record.pid);
}

/** Whether any process is in group [pgid]; never for an id of 1 or below, which `kill` takes as every process. */
function groupExists(pgid: number): boolean {
  return pgid > 1 && processExists(-pgid);
}

/**
 * Whether `kill` reaches [target], a process or, negated, a process group. One it may not signal (EPERM) still exists:
 * only a process or group that's gone (ESRCH) counts as absent.
 */
function processExists(target: number): boolean {
  try {
    process.kill(target, 0);
    return true;
  } catch (error) {
    return !isErrno(error, "ESRCH");
  }
}

/**
 * Stops every task group still running that no Pi tracks, whichever session it belongs to: nothing would end it
 * otherwise, and each process counts toward Android's limit. Its record keeps what was found for its session's report.
 */
export async function stopOrphans(self: Self): Promise<void> {
  const stops = readRecords()
    .filter((record) => !record.exit && !record.orphan?.stopped && tracker(record, self) === "none")
    .filter((record) => groupRunning(record) === true)
    .map(async (record) => {
      const at = Date.now();
      const stopped = await stopGroup(record);
      saveRecord({ ...record, orphan: { at, stopped } });
    });
  await Promise.all(stops);
}

/** Deletes records no Pi tracks once they're a day old, and logs older than that no record names. */
export function pruneRecords(self: Self, now: number): void {
  const cutoff = now - MAX_AGE_MS;
  const named = new Set<string>();
  for (const record of readRecords()) {
    const old = modifiedAt(recordPath(record.key)) < cutoff;
    if (old && tracker(record, self) === "none") removeRecord(record.key);
    else named.add(record.logPath);
  }
  let names: string[];
  try {
    names = readdirSync(taskDir());
  } catch {
    return;
  }
  for (const name of names) {
    const path = join(taskDir(), name);
    if (!name.endsWith(RECORD_SUFFIX) && !named.has(path) && modifiedAt(path) < cutoff) rmSync(path, { force: true });
  }
}

function modifiedAt(path: string): number {
  return statSync(path, { throwIfNoEntry: false })?.mtimeMs ?? 0;
}

/** A time as `14:02` on [now]'s day, with the date before it on any other. */
export function when(at: number, now: number): string {
  const date = new Date(at);
  if (date.toDateString() === new Date(now).toDateString()) return clockTime(at);
  const day = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  return `${day} ${clockTime(at)}`;
}

const STOPPED_BECAUSE: Record<Exclude<StopReason, "agent">, string> = {
  user: "at the user's request",
  timeout: "because its timeout passed",
  quit: "when this chat's agent shut down",
  switch: "when this chat's agent moved to another session",
  exit: "when the agent process running it exited",
};

/**
 * What to tell the agent about [record]'s task, which no Pi tracks and whose result never reached the session; null
 * when there's nothing it doesn't know. [running] is what [groupRunning] found. Only what the record shows is stated
 * as fact: without an exit, how the task ended is unknown, and an empty group doesn't mean nothing it started runs.
 */
export function reportText(record: TaskRecord, running: boolean | undefined, now: number): string | null {
  const name = `background task ${record.id} (${record.label})`;
  const stop = record.stop;
  if (record.orphan) {
    const found = `${capitalize(name)} was still running at ${when(record.orphan.at, now)}, with no agent tracking it`;
    const head = record.orphan.stopped
      ? `${found}, so Pipkin stopped it.`
      : `${found}. Pipkin tried to stop it, but processes in its group (${record.pid}) kept running.`;
    return `${head}\n${outputText(record.logPath)}`;
  }
  if (record.exit) {
    const exit = record.exit;
    if (stop?.reason === "agent") return null;
    const after = `after ${formatDuration(exit.at - record.startedAt)}, at ${when(exit.at, now)}`;
    let head: string;
    if (stop === undefined || stop.reason === "user" || stop.reason === "timeout") {
      head = `${capitalize(name)} ${outcome(exit)} ${after}. Pipkin couldn't pass this result on until now.`;
    } else {
      const how = exit.exitCode === null ? `ended by ${exit.signal ?? "a signal"}` : `exited with code ${exit.exitCode}`;
      head = `Pipkin stopped ${name} ${STOPPED_BECAUSE[stop.reason]}; it ${how} ${after}.`;
    }
    return `${head}\n${outputText(record.logPath)}`;
  }
  if (stop?.reason === "agent") return null;
  const group = running === false ? " No process in its group is running now." : "";
  let head: string;
  if (stop === undefined) {
    head = `Pipkin lost track of ${name} when the agent process running it stopped. How it ended is unknown.${group}`;
  } else if (stop.reason === "user" || stop.reason === "timeout") {
    head = `Pipkin was stopping ${name} ${STOPPED_BECAUSE[stop.reason]} when the agent process running it stopped, so how it ended is unknown.${group}`;
  } else {
    head = `Pipkin began stopping ${name} ${STOPPED_BECAUSE[stop.reason]}. How it ended is unknown.${group}`;
  }
  const written = statSync(record.logPath, { throwIfNoEntry: false })?.mtimeMs;
  const lastWritten = written === undefined ? "" : ` Its log was last written at ${when(written, now)}.`;
  return `${head}${lastWritten}\n${outputText(record.logPath)}`;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
