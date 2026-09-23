import { spawn, spawnSync, type ChildProcessByStdio } from "node:child_process";
import fs from "node:fs";
import { setTimeout as delay } from "node:timers/promises";
import type { Readable, Writable } from "node:stream";
import { need, Refusal } from "./wire.js";

export interface ProcessEntry {
  pid: number;
  ppid: number;
  pgid: number;
  /** Kernel start time (Linux) or launch timestamp (ps); guards against pid reuse. */
  start: string;
}

export interface Owned {
  readonly child: ChildProcessByStdio<Writable, Readable, Readable>;
  readonly pid: number;
  exited: boolean;
  code: number | null;
  signal: NodeJS.Signals | null;
  /** Resolves once the direct child has exited (naturally or by force). */
  readonly exit: Promise<void>;
  closing: Promise<void> | null;
  /** Every descendant ever observed; Pi's bash tool detaches them into their own groups. */
  readonly tracked: Map<number, ProcessEntry>;
}

export interface OwnedOptions {
  cwd: string;
  env: Record<string, string>;
  trackMs?: number;
}

const GRACEFUL_MS = 350;
const TERM_GRACE_MS = 2000;
const CONFIRM_MS = 7000;
const POLL_MS = 25;

/** One pass over the process table: `/proc` on Linux, `ps` elsewhere (host tests). */
export function processTable(): Map<number, ProcessEntry> {
  const table = new Map<number, ProcessEntry>();
  if (process.platform === "linux" || process.platform === "android") {
    for (const name of fs.readdirSync("/proc")) {
      const pid = Number(name);
      if (!Number.isInteger(pid) || pid <= 0) continue;
      let stat: string;
      try {
        stat = fs.readFileSync(`/proc/${name}/stat`, "utf8");
      } catch {
        continue;
      }
      const fields = stat.slice(stat.lastIndexOf(") ") + 2).split(" ");
      const ppid = Number(fields[1]);
      const pgid = Number(fields[2]);
      const start = fields[19];
      if (Number.isInteger(ppid) && Number.isInteger(pgid) && start !== undefined) table.set(pid, { pid, ppid, pgid, start });
    }
    return table;
  }
  const output = spawnSync("ps", ["-axo", "pid=,ppid=,pgid=,lstart="], { encoding: "utf8", timeout: 5000 }).stdout;
  for (const line of output.split("\n")) {
    const match = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.+?)\s*$/.exec(line);
    if (match?.[1] === undefined || match[2] === undefined || match[3] === undefined || match[4] === undefined) continue;
    const pid = Number(match[1]);
    table.set(pid, { pid, ppid: Number(match[2]), pgid: Number(match[3]), start: match[4] });
  }
  return table;
}

export function descendantsOf(root: number, table: Map<number, ProcessEntry>): ProcessEntry[] {
  const found: ProcessEntry[] = [];
  const frontier = [root];
  while (frontier.length > 0) {
    const parent = frontier.pop();
    for (const entry of table.values()) {
      if (entry.ppid === parent && entry.pid !== root) {
        found.push(entry);
        frontier.push(entry.pid);
      }
    }
  }
  return found;
}

function track(owned: Owned): void {
  if (owned.exited) return;
  for (const entry of descendantsOf(owned.pid, processTable())) owned.tracked.set(entry.pid, entry);
}

/**
 * The child leads its own session and process group, so `kill(-pid)` reaches every
 * descendant that stayed in it. Descendants that detached into their own groups are
 * tracked by ppid while the child lives and swept by group on stop. Its stdin is the
 * cooperative stop signal for Pi's RPC mode, which exits on EOF.
 */
export async function spawnOwned(command: string, args: readonly string[], options: OwnedOptions): Promise<Owned> {
  const child = spawn(command, args, {
    cwd: options.cwd,
    env: options.env,
    detached: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdin.on("error", () => undefined);
  const exit: PromiseWithResolvers<void> = Promise.withResolvers();
  const spawned: PromiseWithResolvers<void> = Promise.withResolvers();
  const owned: Owned = {
    child,
    pid: child.pid ?? 0,
    exited: false,
    code: null,
    signal: null,
    closing: null,
    exit: exit.promise,
    tracked: new Map(),
  };
  const tracker = setInterval(() => {
    track(owned);
  }, options.trackMs ?? 2000);
  tracker.unref();
  child.once("exit", (code, signal) => {
    clearInterval(tracker);
    owned.exited = true;
    owned.code = code;
    owned.signal = signal;
    exit.resolve();
  });
  child.once("error", () => {
    clearInterval(tracker);
    owned.exited = true;
    exit.resolve();
    spawned.reject(new Refusal("Termux could not start the owned process"));
  });
  child.once("spawn", spawned.resolve);
  await spawned.promise;
  need(owned.pid > 1, "Termux could not start the owned process");
  return owned;
}

function signalGroup(pid: number, signal: NodeJS.Signals | 0): boolean {
  try {
    process.kill(-pid, signal);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ESRCH") return false;
    throw error;
  }
}

/** Kills the groups of tracked descendants that still exist; returns whether any survive. */
function sweep(owned: Owned, signal: NodeJS.Signals | 0): boolean {
  const table = processTable();
  let alive = false;
  for (const entry of owned.tracked.values()) {
    const current = table.get(entry.pid);
    if (current?.start !== entry.start) continue;
    alive = true;
    if (signal === 0) continue;
    signalGroup(current.pgid, signal);
    try {
      process.kill(entry.pid, signal);
    } catch {
      // Already gone.
    }
  }
  return alive;
}

/** Idempotent; resolves once the direct child has exited and every known descendant is gone. */
export function stopOwned(owned: Owned): Promise<void> {
  owned.closing ??= (async () => {
    track(owned);
    owned.child.stdin.end();
    const graceful = Date.now() + GRACEFUL_MS;
    while (!owned.exited && Date.now() < graceful) await delay(POLL_MS);
    track(owned);
    if (!owned.exited || signalGroup(owned.pid, 0)) signalGroup(owned.pid, "SIGTERM");
    const killAt = Date.now() + TERM_GRACE_MS;
    const deadline = Date.now() + CONFIRM_MS;
    let killed = false;
    do {
      if (owned.exited && !signalGroup(owned.pid, 0) && !sweep(owned, "SIGKILL")) return;
      if (!killed && Date.now() >= killAt) {
        track(owned);
        signalGroup(owned.pid, "SIGKILL");
        killed = true;
      }
      await delay(POLL_MS);
    } while (Date.now() < deadline);
    throw new Error("Owned process cleanup could not be confirmed");
  })();
  return owned.closing;
}
