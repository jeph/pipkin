import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import type * as ChildProcess from "node:child_process";
import { spawn } from "node:child_process";
import type * as Fs from "node:fs";
import { existsSync, readFileSync } from "node:fs";
import { mkdtemp, rm, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import pipkinBackground from "../src/pi-extensions/pipkin-background/index.js";
import {
  bootId,
  processIdentity,
  readRecords,
  reportText,
  saveRecord,
  type TaskRecord,
  when,
} from "../src/pi-extensions/pipkin-background/records.js";
import { REPORT_TYPE, RESULT_TYPE, startProcess, taskDir } from "../src/pi-extensions/pipkin-background/tasks.js";

/** Processes whose start time can't be read, as without permission, rather than because they're gone. */
const unreadable = vi.hoisted(() => new Set<number>());

vi.mock("node:child_process", async (importOriginal) => {
  const actual = await importOriginal<typeof ChildProcess>();
  return {
    ...actual,
    execFileSync: ((file: string, args: string[], options: object) => {
      if (file === "ps" && unreadable.has(Number(args.at(-1)))) {
        throw Object.assign(new Error("ps: operation not permitted"), { status: 2, stdout: "" });
      }
      return actual.execFileSync(file, args, options);
    }) as typeof actual.execFileSync,
  };
});

vi.mock("node:fs", async (importOriginal) => {
  const actual = await importOriginal<typeof Fs>();
  return {
    ...actual,
    readFileSync: ((file: string, options: BufferEncoding) => {
      const pid = typeof file === "string" ? /^\/proc\/(\d+)\/stat$/.exec(file)?.[1] : undefined;
      if (pid !== undefined && unreadable.has(Number(pid))) {
        throw Object.assign(new Error(`EACCES: permission denied, open '${file}'`), { code: "EACCES" });
      }
      return actual.readFileSync(file, options);
    }) as typeof actual.readFileSync,
  };
});

const SHELL = { shell: "/bin/sh", args: ["-c"] };
const REGISTRY = Symbol.for("pipkin.background");

/** Every simulated Pi's registry, so a test's work ends before the next test, and before HOME goes back. */
const registries: {
  tasks: Map<string, { pid: number | undefined; status: string }>;
  bindings: Map<string, unknown>;
  orphans: Promise<void>;
}[] = [];

interface Sent {
  message: { customType: string; content: string; details: { tasks: { key: string }[] } };
  options: { triggerTurn?: boolean };
}

interface Session {
  id: string;
  entries: unknown[];
  idle: boolean;
}

/**
 * One Pi process loading the extension for [session], as far as the extension sees it: a new registry, Pi's events,
 * the `background` tool and the messages it sends. A message sent while idle that starts no run joins the session, as
 * Pi appends it then.
 */
function loadPi(session: Session) {
  delete (globalThis as { [REGISTRY]?: unknown })[REGISTRY];
  const handlers = new Map<string, ((event: unknown, ctx: unknown) => unknown)[]>();
  let tool: { execute: (...args: unknown[]) => Promise<{ content: { text: string }[] }> } | undefined;
  const sent: Sent[] = [];
  const ctx = {
    sessionManager: { getSessionId: () => session.id, getEntries: () => session.entries },
    cwd: tmpdir(),
    hasUI: false,
    isIdle: () => session.idle,
  };
  const pi = {
    on: (name: string, handler: (event: unknown, ctx: unknown) => unknown) => {
      handlers.set(name, [...(handlers.get(name) ?? []), handler]);
    },
    registerTool: (registered: typeof tool) => {
      tool = registered;
    },
    getAllTools: () => [],
    sendMessage: (message: Sent["message"], options: Sent["options"]) => {
      sent.push({ message, options });
      if (session.idle && !options.triggerTurn) session.entries.push({ type: "custom_message", ...message });
    },
  };
  pipkinBackground(pi as unknown as ExtensionAPI);
  const loaded = (globalThis as { [REGISTRY]?: (typeof registries)[number] })[REGISTRY];
  if (!loaded) throw new Error("the extension loaded no registry");
  registries.push(loaded);
  return {
    sent,
    emit: async (name: string, event: unknown = {}) => {
      for (const handler of handlers.get(name) ?? []) await handler(event, ctx);
    },
    call: async (params: Record<string, unknown>) => {
      const result = await tool?.execute("call", params, undefined, undefined, ctx);
      return result?.content[0]?.text ?? "";
    },
  };
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/** Polls [check] for up to [ms]: the processes here are real and say nothing when they start or end. */
async function eventually(check: () => boolean, ms = 3000): Promise<boolean> {
  for (let waited = 0; waited < ms; waited += 20) {
    if (check()) return true;
    await sleep(20);
  }
  return check();
}

/** A process that has exited, standing in for a Pi that died: its pid with an identity no process has. */
async function deadProcess(): Promise<{ pid: number; identity: string }> {
  const child = spawn("/bin/sh", ["-c", "exit 0"]);
  await new Promise((resolve) => child.once("exit", resolve));
  return { pid: child.pid ?? 0, identity: "ended" };
}

/** Saves the record a Pi now dead left of task bg1 in session s1, whose process has exited too unless [overrides] say. */
async function record(overrides: Partial<TaskRecord> = {}): Promise<TaskRecord> {
  const owner = await deadProcess();
  const task = await deadProcess();
  const written: TaskRecord = {
    key: "k1",
    id: "bg1",
    label: "build",
    command: "make",
    sessionId: "s1",
    logPath: path.join(taskDir(), "k1.log"),
    startedAt: Date.now() - 125_000,
    pid: task.pid,
    identity: task.identity,
    owner,
    boot: bootId(),
    ...overrides,
  };
  saveRecord(written);
  return written;
}

/** A `background` start result as Pi saves it in the session. */
function startResult(id: string, toolName = "background") {
  return {
    type: "message",
    message: { role: "toolResult", toolName, content: [{ type: "text", text: `Started background task ${id} (x).` }] },
  };
}

describe("background task records", () => {
  let home: string;
  let session: Session;
  const originalHome = process.env.HOME;
  /** HOME between tests, so anything a test's Pi still writes after it lands there instead of the real home. */
  let between: string;
  const groups: number[] = [];
  let exitListeners: number;

  beforeAll(async () => {
    between = await mkdtemp(path.join(tmpdir(), "pipkin-records-between-"));
  });

  afterAll(async () => {
    process.env.HOME = originalHome;
    await rm(between, { recursive: true, force: true });
  });

  beforeEach(async () => {
    home = await mkdtemp(path.join(tmpdir(), "pipkin-records-"));
    process.env.HOME = home;
    session = { id: "s1", entries: [], idle: true };
    exitListeners = process.listenerCount("exit");
  });

  afterEach(async () => {
    unreadable.clear();
    vi.restoreAllMocks();
    const loaded = registries.splice(0);
    // A session with no binding takes no further look at its records, which ends any recheck still scheduled.
    for (const registry of loaded) registry.bindings.clear();
    const tasks = loaded.flatMap((registry) => [...registry.tasks.values()]);
    const pids = [...groups.splice(0), ...tasks.flatMap((task) => (task.status === "running" && task.pid ? [task.pid] : []))];
    for (const pid of pids) {
      try {
        process.kill(-pid, "SIGKILL");
      } catch {
        // Already gone.
      }
    }
    // A task's exit and an orphan's stop write records under HOME, so they must land in this test's home.
    await Promise.all(loaded.map((registry) => registry.orphans));
    if (!(await eventually(() => tasks.every((task) => task.status !== "running")))) throw new Error("a task outlived its test");
    // Each simulated Pi adds an exit hook for its tasks; theirs are gone now.
    for (const listener of process.listeners("exit").slice(exitListeners)) process.off("exit", listener);
    delete (globalThis as { [REGISTRY]?: unknown })[REGISTRY];
    process.env.HOME = between;
    await rm(home, { recursive: true, force: true });
  });

  /** A task group running outside any Pi, as one left behind by a Pi that died. */
  async function orphanGroup(command = "sleep 300"): Promise<{ pid: number; identity: string }> {
    const started = startProcess(SHELL, command, home, path.join(taskDir(), "orphan.log"));
    const pid = started.pid ?? 0;
    groups.push(pid);
    started.ended.catch(() => undefined);
    const identity = processIdentity(pid);
    if (identity === undefined) throw new Error("no identity");
    return { pid, identity };
  }

  describe("a running Pi", () => {
    it("records a task as it starts and its exit before the result goes out", async () => {
      const pi = loadPi(session);
      await pi.emit("session_start");
      const reply = await pi.call({ action: "start", command: "echo built; exit 3", label: "build" });
      expect(reply).toMatch(/^Started background task bg1 \(build\)/);
      const [started] = readRecords();
      expect(started).toMatchObject({ id: "bg1", label: "build", sessionId: "s1", owner: { pid: process.pid } });
      expect(started?.owner.identity).toBe(processIdentity(process.pid));
      expect(started?.boot).toBe(bootId());

      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(pi.sent[0]?.message.details.tasks[0]?.key).toBe(started?.key);
      expect(readRecords()[0]?.exit).toMatchObject({ status: "exited", exitCode: 3, signal: null });
    });

    it("drops the record of a task the agent stopped", async () => {
      const pi = loadPi(session);
      await pi.emit("session_start");
      await pi.call({ action: "start", command: "sleep 300" });
      expect(readRecords()).toHaveLength(1);
      await pi.call({ action: "stop", id: "bg1" });
      expect(await eventually(() => readRecords().length === 0)).toBe(true);
      expect(pi.sent).toEqual([]);
    });

    it("records a timeout as the reason it stopped the task", async () => {
      const pi = loadPi(session);
      await pi.emit("session_start");
      await pi.call({ action: "start", command: "sleep 300", timeout: 0.1 });
      expect(await eventually(() => readRecords()[0]?.exit !== undefined)).toBe(true);
      expect(readRecords()[0]).toMatchObject({ stop: { reason: "timeout" }, exit: { status: "timed_out" } });
    });

    it("clears a record once the session holds the task's result", async () => {
      const pi = loadPi(session);
      await pi.emit("session_start");
      await pi.call({ action: "start", command: "true" });
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      // The result woke the agent, and Pi saved it with the run that followed.
      session.entries.push({ type: "custom_message", ...pi.sent[0]?.message });
      expect(readRecords()).toHaveLength(1);
      await pi.emit("before_agent_start", { messageType: "user" });
      expect(readRecords()).toEqual([]);
    });

    it("reports a task it stopped on leaving the session when the session comes back", async () => {
      const pi = loadPi(session);
      await pi.emit("session_start");
      await pi.call({ action: "start", command: "sleep 300", label: "server" });
      await pi.emit("session_shutdown", { reason: "new" });
      expect(await eventually(() => readRecords()[0]?.exit !== undefined)).toBe(true);

      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(pi.sent[0]?.options).toEqual({ triggerTurn: false });
      expect(pi.sent[0]?.message.content).toMatch(
        /^Pipkin stopped background task bg1 \(server\) when this chat's agent moved to another session; it ended by SIGTERM after \d+s, at /,
      );
    });

    it("numbers new tasks past the ones the session's records name", async () => {
      await record({ id: "bg7", exit: { status: "exited", exitCode: 0, signal: null, at: Date.now() } });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await pi.call({ action: "start", command: "true" })).toMatch(/^Started background task bg8 /);
    });

    it("numbers new tasks past every id the session's history names", async () => {
      const result = { type: "custom_message", customType: RESULT_TYPE, details: { tasks: [{ key: "old", id: "bg4" }] } };
      session.entries.push(startResult("bg3"), result, startResult("bg99", "bash"));
      const first = loadPi(session);
      await first.emit("session_start");
      expect(await first.call({ action: "start", command: "true" })).toMatch(/^Started background task bg5 /);
      expect(await eventually(() => first.sent.length > 0)).toBe(true);

      session.entries.push({ type: "custom_message", customType: REPORT_TYPE, details: { tasks: [{ key: "lost", id: "bg11" }] } });
      const later = loadPi(session);
      await later.emit("session_start");
      expect(await later.call({ action: "start", command: "true" })).toMatch(/^Started background task bg12 /);
      expect(await eventually(() => later.sent.length > 0)).toBe(true);
    });
  });

  describe("a later Pi on the session", () => {
    it("passes on a result its Pi recorded but never delivered, without waking the agent", async () => {
      const exitAt = Date.now() - 5_000;
      const written = await record({ startedAt: exitAt - 125_000, exit: { status: "exited", exitCode: 0, signal: null, at: exitAt } });
      await writeFile(written.logPath, "compiled\n");
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(pi.sent).toEqual([
        {
          message: {
            customType: REPORT_TYPE,
            content: `Background task bg1 (build) exit 0 after 2m 5s, at ${when(exitAt, Date.now())}. Pipkin couldn't pass this result on until now.\nLast output:\n\`\`\`\ncompiled\n\`\`\`\nFull output: ${written.logPath}`,
            display: true,
            details: { tasks: [{ key: "k1", id: "bg1", label: "build" }] },
          },
          options: { triggerTurn: false },
        },
      ]);
    });

    it("says nothing about a result the session already holds, and clears its record", async () => {
      await record({ exit: { status: "exited", exitCode: 0, signal: null, at: Date.now() } });
      session.entries.push({ type: "custom_message", customType: RESULT_TYPE, details: { tasks: [{ key: "k1" }] } });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => readRecords().length === 0)).toBe(true);
      expect(pi.sent).toEqual([]);
    });

    it("reports a task once, even before its report reaches the session", async () => {
      await record({ exit: { status: "exited", exitCode: 0, signal: null, at: Date.now() } });
      session.idle = false;
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      await pi.emit("session_start");
      await sleep(200);
      expect(pi.sent).toHaveLength(1);
    });

    it("stops a task group still running that no Pi tracks, children included, and says so", async () => {
      const pidFile = path.join(home, "child.pid");
      const group = await orphanGroup(`sleep 300 & echo $! > ${pidFile}.tmp; mv ${pidFile}.tmp ${pidFile}; wait`);
      expect(await eventually(() => existsSync(pidFile))).toBe(true);
      const child = Number(readFileSync(pidFile, "utf8").trim());
      await record({ pid: group.pid, identity: group.identity });

      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0, 6000)).toBe(true);
      expect(alive(group.pid)).toBe(false);
      expect(alive(child)).toBe(false);
      expect(pi.sent[0]?.message.content).toMatch(
        /^Background task bg1 \(build\) was still running at \d\d:\d\d, with no agent tracking it, so Pipkin stopped it\.\n/,
      );
    });

    it("stops another session's orphaned group too, keeping the report for that session", async () => {
      const group = await orphanGroup();
      await record({ sessionId: "s2", pid: group.pid, identity: group.identity });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => !alive(group.pid), 6000)).toBe(true);
      expect(await eventually(() => readRecords()[0]?.orphan?.stopped === true)).toBe(true);
      expect(pi.sent).toEqual([]);
    });

    it("calls the outcome unknown when its Pi recorded no exit and nothing in its group runs", async () => {
      const written = await record();
      await writeFile(written.logPath, "listening on :3000\n");
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(pi.sent[0]?.message.content).toBe(
        `Pipkin lost track of background task bg1 (build) when the agent process running it stopped. How it ended is unknown. No process in its group is running now. Its log was last written at ${when(Date.now(), Date.now())}.\nLast output:\n\`\`\`\nlistening on :3000\n\`\`\`\nFull output: ${written.logPath}`,
      );
    });

    it("leaves alone a group whose id went to a later process", async () => {
      const later = await orphanGroup();
      await record({ pid: later.pid, identity: "an earlier process" });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(alive(later.pid)).toBe(true);
      expect(pi.sent[0]?.message.content).toMatch(/How it ended is unknown\. No process in its group is running now\./);
    });

    it("leaves alone a group whose leader is gone, which could be a later group with that id", async () => {
      const pidFile = path.join(home, "child.pid");
      const group = await orphanGroup(`sleep 300 & echo $! > ${pidFile}.tmp; mv ${pidFile}.tmp ${pidFile}`);
      expect(await eventually(() => existsSync(pidFile))).toBe(true);
      const child = Number(readFileSync(pidFile, "utf8").trim());
      expect(await eventually(() => processIdentity(group.pid) === undefined)).toBe(true);
      await record({ pid: group.pid, identity: group.identity });

      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(alive(child)).toBe(true);
      expect(pi.sent[0]?.message.content).toMatch(/How it ended is unknown\.\n/);
    });

    it("takes every process from before a restart as ended, whatever now has its ids", async () => {
      const later = await orphanGroup();
      await record({ pid: later.pid, identity: later.identity, boot: "an earlier boot" });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(alive(later.pid)).toBe(true);
      expect(pi.sent[0]?.message.content).toMatch(/No process in its group is running now\./);
    });

    it("leaves tasks a running Pi tracks, then reports them once that Pi is gone", async () => {
      const owner = await orphanGroup();
      const task = await orphanGroup();
      await record({ owner, pid: task.pid, identity: task.identity });
      const pi = loadPi(session);
      await pi.emit("session_start");
      await sleep(300);
      expect(pi.sent).toEqual([]);
      expect(alive(task.pid)).toBe(true);

      process.kill(-owner.pid, "SIGKILL");
      expect(await eventually(() => pi.sent.length > 0, 8000)).toBe(true);
      expect(alive(task.pid)).toBe(false);
      expect(pi.sent[0]?.message.content).toMatch(/was still running at .*, so Pipkin stopped it\./);
    });

    it("takes a Pi it couldn't identify as running while any process has its id", async () => {
      const owner = await orphanGroup();
      await record({ owner: { pid: owner.pid, identity: null } });
      const pi = loadPi(session);
      await pi.emit("session_start");
      await sleep(300);
      expect(pi.sent).toEqual([]);
      expect(readRecords()).toHaveLength(1);

      process.kill(-owner.pid, "SIGKILL");
      expect(await eventually(() => pi.sent.length > 0, 8000)).toBe(true);
    });

    it("takes a Pi whose start time can't be read as running, not gone", async () => {
      const owner = await orphanGroup();
      const task = await orphanGroup();
      await record({ owner, pid: task.pid, identity: task.identity });
      unreadable.add(owner.pid);
      const pi = loadPi(session);
      await pi.emit("session_start");
      await sleep(300);
      expect(pi.sent).toEqual([]);
      expect(alive(task.pid)).toBe(true);
      expect(readRecords()).toHaveLength(1);
    });

    it("takes a Pi it may not signal as running, not gone", async () => {
      const owner = await deadProcess();
      await record({ owner: { pid: owner.pid, identity: null } });
      const kill = process.kill.bind(process);
      vi.spyOn(process, "kill").mockImplementation((target, signal) => {
        if (target === owner.pid) throw Object.assign(new Error("kill EPERM"), { code: "EPERM" });
        return kill(target, signal);
      });
      const pi = loadPi(session);
      await pi.emit("session_start");
      await sleep(300);
      expect(pi.sent).toEqual([]);
      expect(readRecords()).toHaveLength(1);
    });

    it("neither stops nor rules out a group whose leader can't be read", async () => {
      const task = await orphanGroup();
      await record({ pid: task.pid, identity: task.identity });
      unreadable.add(task.pid);
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(alive(task.pid)).toBe(true);
      expect(pi.sent[0]?.message.content).toMatch(/How it ended is unknown\.\n/);
    });

    it("doesn't rule out a group it may not signal", async () => {
      const task = await deadProcess();
      await record({ pid: task.pid, identity: "ended" });
      const kill = process.kill.bind(process);
      vi.spyOn(process, "kill").mockImplementation((target, signal) => {
        if (target === -task.pid) throw Object.assign(new Error("kill EPERM"), { code: "EPERM" });
        return kill(target, signal);
      });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(pi.sent[0]?.message.content).toMatch(/How it ended is unknown\.\n/);
    });

    it("doesn't report another session's tasks", async () => {
      await record({ sessionId: "s2", exit: { status: "exited", exitCode: 0, signal: null, at: Date.now() } });
      const pi = loadPi(session);
      await pi.emit("session_start");
      await sleep(300);
      expect(pi.sent).toEqual([]);
      expect(readRecords()).toHaveLength(1);
    });

    it("says nothing of a task the agent itself stopped, and clears its record", async () => {
      await record({ stop: { reason: "agent", at: Date.now() } });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => readRecords().length === 0)).toBe(true);
      expect(pi.sent).toEqual([]);
    });

    it("puts every task in one report", async () => {
      await record();
      await record({ key: "k2", id: "bg2", label: "tests", logPath: path.join(taskDir(), "k2.log") });
      const pi = loadPi(session);
      await pi.emit("session_start");
      expect(await eventually(() => pi.sent.length > 0)).toBe(true);
      expect(pi.sent).toHaveLength(1);
      expect(pi.sent[0]?.message.details.tasks.map((task) => task.key).toSorted()).toEqual(["k1", "k2"]);
    });

    it("skips unreadable records", async () => {
      await writeFile(path.join(taskDir(), "broken.json"), "{");
      await writeFile(path.join(taskDir(), "partial.json"), JSON.stringify({ key: "partial", id: "bg1" }));
      const pi = loadPi(session);
      await pi.emit("session_start");
      await sleep(300);
      expect(pi.sent).toEqual([]);
    });

    it("deletes day-old records no Pi tracks, and logs no record names", async () => {
      const stale = await record({ key: "old", logPath: path.join(taskDir(), "old.log") });
      await writeFile(stale.logPath, "old\n");
      const day = new Date(Date.now() - 25 * 60 * 60 * 1000);
      await utimes(path.join(taskDir(), "old.json"), day, day);
      const tracked = await record({ key: "live", sessionId: "s2", owner: await orphanGroup(), logPath: path.join(taskDir(), "live.log") });
      await writeFile(tracked.logPath, "live\n");
      await utimes(path.join(taskDir(), "live.json"), day, day);
      await utimes(tracked.logPath, day, day);
      await writeFile(path.join(taskDir(), "loose.log"), "loose\n");
      await utimes(path.join(taskDir(), "loose.log"), day, day);

      loadPi({ ...session, id: "s3" });
      expect(await eventually(() => !existsSync(path.join(taskDir(), "loose.log")))).toBe(true);
      expect(readRecords().map((kept) => kept.key)).toEqual(["live"]);
      expect(existsSync(tracked.logPath)).toBe(true);
    });
  });
});

describe("report wording", () => {
  const now = new Date(2026, 9, 18, 15, 0).getTime();
  const base: TaskRecord = {
    key: "k1",
    id: "bg1",
    label: "server",
    command: "npm run dev",
    sessionId: "s1",
    logPath: "/nowhere/k1.log",
    startedAt: new Date(2026, 9, 18, 14, 0).getTime(),
    pid: 100,
    identity: "1",
    owner: { pid: 99, identity: "1" },
    boot: "b",
  };
  const gone = "Its log file /nowhere/k1.log is gone.";

  it("names the stop Pipkin began, and the exit it got", () => {
    const exit = { status: "stopped" as const, exitCode: null, signal: "SIGTERM", at: new Date(2026, 9, 18, 14, 30).getTime() };
    expect(reportText({ ...base, stop: { reason: "quit", at: exit.at }, exit }, false, now)).toBe(
      `Pipkin stopped background task bg1 (server) when this chat's agent shut down; it ended by SIGTERM after 30m 0s, at 14:30.\n${gone}`,
    );
    expect(reportText({ ...base, stop: { reason: "user", at: exit.at }, exit }, false, now)).toBe(
      `Background task bg1 (server) stopped by the user after 30m 0s, at 14:30. Pipkin couldn't pass this result on until now.\n${gone}`,
    );
  });

  it("says a stop was under way, not that it worked, when no exit came", () => {
    expect(reportText({ ...base, stop: { reason: "timeout", at: now } }, false, now)).toBe(
      `Pipkin was stopping background task bg1 (server) because its timeout passed when the agent process running it stopped, so how it ended is unknown. No process in its group is running now.\n${gone}`,
    );
    expect(reportText({ ...base, stop: { reason: "exit", at: now } }, false, now)).toBe(
      `Pipkin began stopping background task bg1 (server) when the agent process running it exited. How it ended is unknown. No process in its group is running now.\n${gone}`,
    );
  });

  it("claims nothing about the group when it can't be told apart", () => {
    expect(reportText(base, undefined, now)).toBe(
      `Pipkin lost track of background task bg1 (server) when the agent process running it stopped. How it ended is unknown.\n${gone}`,
    );
  });

  it("owns up to a group that outlived its stop", () => {
    expect(reportText({ ...base, orphan: { at: new Date(2026, 9, 18, 14, 59).getTime(), stopped: false } }, true, now)).toBe(
      `Background task bg1 (server) was still running at 14:59, with no agent tracking it. Pipkin tried to stop it, but processes in its group (100) kept running.\n${gone}`,
    );
  });

  it("dates times from another day", () => {
    expect(when(new Date(2026, 9, 17, 23, 5).getTime(), now)).toBe("2026-10-17 23:05");
    expect(when(new Date(2026, 9, 18, 0, 5).getTime(), now)).toBe("00:05");
  });
});
