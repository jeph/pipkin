/**
 * Pipkin background: the `background` tool, which runs shell commands past the turn that started them and brings each
 * one's result back as a message that wakes the agent. Pipkin stops an idle chat's Pi and lets the phone sleep once no
 * chat works, so the extension tells the app over Pipkin's channel how many tasks still need this Pi: the app keeps the
 * chat's Pi and its wake lock while any do.
 *
 * Tasks belong to the Pi process, not to one load of the extension: Pi loads it again for each session and `/reload`,
 * and every load shares one registry, so the count the app sees covers all of them. A `/reload` keeps its session's
 * tasks; moving to another session or quitting stops them.
 *
 * Each task also has a record on disk (`./records.ts`), so when a Pi stops without passing on what became of its
 * tasks, as when Android ends Termux, the next Pi on the session tells the agent what the records show.
 */
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { getShellConfig } from "@earendil-works/pi-coding-agent";
import { statSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import { isObject } from "../../framing.js";
import { type PipkinChannel, pipkinChannel } from "../pipkin-channel.js";
import {
  bootId,
  groupRunning,
  recordedIdentity,
  pruneRecords,
  readRecords,
  removeRecord,
  reportText,
  type Self,
  saveRecord,
  type StopReason,
  stopGroup,
  stopOrphans,
  type TaskRecord,
  tracker,
} from "./records.js";
import {
  BackgroundParams,
  defaultLabel,
  listText,
  MAX_RUNNING,
  PROMPT_GUIDELINES,
  PROMPT_SNIPPET,
  REPORT_TYPE,
  RESULT_TYPE,
  resultDetails,
  resultText,
  signalGroup,
  startProcess,
  type Task,
  taskDir,
  TOOL_DESCRIPTION,
  TOOL_LABEL,
  TOOL_NAME,
  TOPIC,
  widgetLines,
} from "./tasks.js";

/** The widget's key, which Pipkin names its tab after. */
export const WIDGET_KEY = "background";

/** How long a stopped task gets to exit on SIGTERM before SIGKILL. */
const KILL_AFTER_MS = 3000;

/** How often, and how many times, a session looks again at tasks another Pi still ran when it started. */
const RECHECK_MS = 1000;
const RECHECKS = 30;

/**
 * Why a task was ended early. The agent's own `stop` already answered and a session being left (`quit`, `switch`) has
 * no one to tell, so neither sends a result; the user's Stop tells the agent without waking it; a timeout is a result
 * like any other.
 */
type EndReason = Exclude<StopReason, "exit">;

function leftSession(reason: EndReason | undefined): boolean {
  return reason === "quit" || reason === "switch";
}

interface RunningTask extends Task {
  pid: number | undefined;
  endReason?: EndReason;
  timer?: NodeJS.Timeout;
  /** What's on disk about the task; undefined when it has no process. */
  record?: TaskRecord;
}

/** A session's latest load of the extension, which its results and widget go through. */
interface Binding {
  pi: ExtensionAPI;
  ctx: ExtensionContext;
}

/** Everything one Pi process knows about its tasks, shared by every load of the extension. */
interface Registry {
  tasks: Map<string, RunningTask>;
  nextId: number;
  /** Finished tasks whose result no session has received yet, by session id. */
  undelivered: Map<string, RunningTask[]>;
  /** Results sent with a new run, counted until that run starts so the app keeps Pi through the handoff. */
  delivering: number;
  bindings: Map<string, Binding>;
  channel: PipkinChannel;
  lastReported?: number;
  /** This Pi as records name it. */
  self: Self;
  /** Settles once the task groups no Pi tracked when this one started are stopped. */
  orphans: Promise<void>;
  /** Records this Pi already told a session about, whose message may not be in the session yet. */
  reported: Set<string>;
}

const SHARED = Symbol.for("pipkin.background");

function registry(): Registry {
  const global = globalThis as { [SHARED]?: Registry };
  if (global[SHARED]) return global[SHARED];
  const tasks = new Map<string, RunningTask>();
  const self: Self = {
    pid: process.pid,
    identity: recordedIdentity(process.pid),
    tracks: (key) => [...tasks.values()].some((task) => task.key === key),
  };
  const created: Registry = {
    tasks,
    nextId: 1,
    undelivered: new Map(),
    delivering: 0,
    bindings: new Map(),
    channel: pipkinChannel(),
    self,
    // A failure here or in a reconcile leaves records for a later Pi: a report goes missing, nothing false is said.
    orphans: stopOrphans(self)
      .then(() => pruneRecords(self, Date.now()))
      .catch(() => undefined),
    reported: new Set(),
  };
  global[SHARED] = created;
  created.channel.on(TOPIC, (data) => {
    if (!isObject(data) || data.action !== "stop") return;
    for (const task of created.tasks.values()) if (task.status === "running") stop(task, "user");
  });
  // Pi exits on stdin EOF after `session_shutdown`; this catches a crash, so no task outlives its Pi.
  process.once("exit", () => {
    for (const task of created.tasks.values()) {
      if (task.status === "running" && signalGroup(task.pid, "SIGKILL")) recordStop(task, "exit");
    }
  });
  return created;
}

/** Tells the app how many tasks keep this Pi needed: running ones and results on their way to the agent. */
function report(state: Registry): void {
  let active = state.delivering;
  for (const task of state.tasks.values()) if (task.status === "running") active++;
  for (const tasks of state.undelivered.values()) active += tasks.length;
  if (active === state.lastReported) return;
  state.lastReported = active;
  state.channel.send(TOPIC, { active });
}

function showWidget(state: Registry, sessionId: string): void {
  const binding = state.bindings.get(sessionId);
  if (!binding?.ctx.hasUI) return;
  const tasks = [...state.tasks.values()].filter((task) => task.sessionId === sessionId);
  binding.ctx.ui.setWidget(WIDGET_KEY, widgetLines(tasks, binding.ctx.ui.theme) ?? undefined);
}

/** Notes in [task]'s record that Pipkin began stopping it, unless it already had. */
function recordStop(task: RunningTask, reason: StopReason): void {
  if (!task.record || task.record.stop) return;
  task.record = { ...task.record, stop: { reason, at: Date.now() } };
  saveRecord(task.record);
}

/** Ends [task]'s process group: SIGTERM, then SIGKILL if it's still there. */
function stop(task: RunningTask, reason: EndReason): void {
  if (task.endReason === undefined) {
    task.endReason = reason;
    recordStop(task, reason);
  }
  if (!signalGroup(task.pid, "SIGTERM")) return;
  setTimeout(() => {
    if (task.status === "running") signalGroup(task.pid, "SIGKILL");
  }, KILL_AFTER_MS).unref();
}

function finished(state: Registry, task: RunningTask, code: number | null, signal: string | null, error?: string): void {
  clearTimeout(task.timer);
  // Anything the command left running in its group, such as a `&` inside it, goes too: nothing would track it.
  signalGroup(task.pid, "SIGKILL");
  task.endedAt = Date.now();
  task.exitCode = code;
  task.signal = signal;
  if (error !== undefined) {
    task.status = "failed";
    task.error = error;
  } else if (task.endReason === "timeout") {
    task.status = "timed_out";
  } else {
    task.status = task.endReason ? "stopped" : "exited";
  }
  // The agent's own stop needs no later word; any other exit is noted before its result goes out, which a Pi stopping
  // now could leave undelivered.
  const record = task.record;
  if (record && task.endReason === "agent") {
    removeRecord(task.key);
  } else if (record) {
    const exit = { status: task.status, exitCode: code, signal, at: task.endedAt, ...(error === undefined ? {} : { error }) };
    task.record = { ...record, exit };
    saveRecord(task.record);
  }
  if (leftSession(task.endReason)) {
    state.tasks.delete(task.id);
  } else if (task.endReason !== "agent") {
    const waiting = state.undelivered.get(task.sessionId) ?? [];
    waiting.push(task);
    state.undelivered.set(task.sessionId, waiting);
  }
  deliver(state, task.sessionId);
  showWidget(state, task.sessionId);
}

/**
 * Sends a session its finished tasks' results once it's idle, waking the agent; a busy session gets them when its run
 * settles. Results only the user's Stop ended, or arriving as the user stops a run, wake nobody: the agent reads them
 * with the next prompt.
 */
function deliver(state: Registry, sessionId: string, settled?: { aborted: boolean }): void {
  const binding = state.bindings.get(sessionId);
  const tasks = state.undelivered.get(sessionId);
  if (binding && tasks?.length && (settled || binding.ctx.isIdle())) {
    state.undelivered.delete(sessionId);
    const wake = !settled?.aborted && tasks.some((task) => task.endReason !== "user");
    if (wake) state.delivering += tasks.length;
    binding.pi.sendMessage(
      { customType: RESULT_TYPE, content: resultText(tasks), display: true, details: resultDetails(tasks) },
      { triggerTurn: wake },
    );
  }
  report(state);
}

/** Stops a session's tasks without telling the agent, which is leaving the session. */
function leaveSession(state: Registry, sessionId: string, reason: "quit" | "switch"): void {
  for (const task of state.tasks.values()) {
    if (task.sessionId !== sessionId) continue;
    if (task.status === "running") stop(task, reason);
    else state.tasks.delete(task.id);
  }
  state.undelivered.delete(sessionId);
  state.bindings.delete(sessionId);
}

/** The keys of the tasks whose result or report [ctx]'s session holds. */
function deliveredKeys(ctx: ExtensionContext): Set<string> {
  const keys = new Set<string>();
  for (const entry of ctx.sessionManager.getEntries()) {
    if (entry.type !== "custom_message" || (entry.customType !== RESULT_TYPE && entry.customType !== REPORT_TYPE)) continue;
    const tasks = isObject(entry.details) && Array.isArray(entry.details.tasks) ? entry.details.tasks : [];
    for (const task of tasks) if (isObject(task) && typeof task.key === "string") keys.add(task.key);
  }
  return keys;
}

/** Deletes the records of [sessionId]'s tasks whose result or report its session now holds. */
function settleRecords(sessionId: string, ctx: ExtensionContext): void {
  const delivered = deliveredKeys(ctx);
  for (const record of readRecords()) {
    if (record.sessionId === sessionId && delivered.has(record.key)) removeRecord(record.key);
  }
}

/**
 * Moves this Pi's numbering past every task id [ctx]'s session names, in its `background` results, task results and
 * reports, or in its records, so a new task never takes an id the agent may still read about. Any id seen counts,
 * since numbering past one costs nothing.
 */
function skipUsedIds(state: Registry, sessionId: string, ctx: ExtensionContext): void {
  const use = (id: unknown) => {
    const number = typeof id === "string" ? /^bg(\d+)$/.exec(id)?.[1] : undefined;
    if (number !== undefined) state.nextId = Math.max(state.nextId, Number(number) + 1);
  };
  for (const entry of ctx.sessionManager.getEntries()) {
    if (entry.type === "custom_message" && (entry.customType === RESULT_TYPE || entry.customType === REPORT_TYPE)) {
      const tasks = isObject(entry.details) && Array.isArray(entry.details.tasks) ? entry.details.tasks : [];
      for (const task of tasks) if (isObject(task)) use(task.id);
    }
    if (entry.type !== "message" || entry.message.role !== "toolResult" || entry.message.toolName !== TOOL_NAME) continue;
    for (const block of entry.message.content) {
      if (block.type !== "text") continue;
      for (const match of block.text.matchAll(/\bbg\d+\b/g)) use(match[0]);
    }
  }
  for (const record of readRecords()) if (record.sessionId === sessionId) use(record.id);
}

/**
 * Tells [binding]'s session what the records show of its tasks that no Pi tracks and whose result never reached it,
 * as one message read with the next prompt: an agent coming back to a chat shouldn't start work on its own. A group
 * found running goes first. Tasks another running Pi tracks are looked at again until it's gone.
 */
async function reconcile(state: Registry, sessionId: string, binding: Binding, rechecks = RECHECKS): Promise<void> {
  await state.orphans;
  if (state.bindings.get(sessionId) !== binding) return;
  const delivered = deliveredKeys(binding.ctx);
  const reports: { record: TaskRecord; text: string }[] = [];
  let trackedElsewhere = false;
  for (let record of readRecords()) {
    if (record.sessionId !== sessionId || state.reported.has(record.key)) continue;
    if (delivered.has(record.key)) {
      removeRecord(record.key);
      continue;
    }
    const who = tracker(record, state.self);
    trackedElsewhere ||= who === "other";
    if (who !== "none") continue;
    const running = record.exit ? false : groupRunning(record);
    if (running === true) {
      record = { ...record, orphan: { at: Date.now(), stopped: await stopGroup(record) } };
      saveRecord(record);
    }
    const text = reportText(record, running, Date.now());
    if (text === null) removeRecord(record.key);
    else reports.push({ record, text });
  }
  if (state.bindings.get(sessionId) !== binding) return;
  if (reports.length > 0) {
    for (const { record } of reports) state.reported.add(record.key);
    binding.pi.sendMessage(
      {
        customType: REPORT_TYPE,
        content: reports.map(({ text }) => text).join("\n\n"),
        display: true,
        details: { tasks: reports.map(({ record }) => ({ key: record.key, id: record.id, label: record.label })) },
      },
      { triggerTurn: false },
    );
  }
  if (trackedElsewhere && rechecks > 0) {
    setTimeout(() => reconcile(state, sessionId, binding, rechecks - 1).catch(() => undefined), RECHECK_MS).unref();
  }
}

function start(state: Registry, params: BackgroundParams, ctx: ExtensionContext): string {
  const command = params.command?.trim();
  if (!command) return "Error: command required for start";
  const running = [...state.tasks.values()].filter((task) => task.status === "running").length;
  if (running >= MAX_RUNNING) {
    return `Error: ${running} background tasks are already running, the most Pipkin allows. Stop one or wait for one to finish.`;
  }
  const cwd = params.cwd ? (isAbsolute(params.cwd) ? params.cwd : resolve(ctx.cwd, params.cwd)) : ctx.cwd;
  // Node reports a missing cwd as the shell not being found, so it's checked here.
  if (!statSync(cwd, { throwIfNoEntry: false })?.isDirectory()) return `Error: ${cwd} is not a directory`;
  const id = `bg${state.nextId++}`;
  const startedAt = Date.now();
  const key = `${startedAt.toString(36)}-${process.pid}-${id}`;
  const task: RunningTask = {
    key,
    id,
    label: params.label?.trim() || defaultLabel(command),
    command,
    cwd,
    sessionId: ctx.sessionManager.getSessionId(),
    logPath: join(taskDir(), `${key}.log`),
    startedAt,
    status: "running",
    pid: undefined,
  };
  try {
    const started = startProcess(getShellConfig(), command, cwd, task.logPath);
    task.pid = started.pid;
    if (started.pid !== undefined) {
      task.record = {
        key,
        id,
        label: task.label,
        command,
        sessionId: task.sessionId,
        logPath: task.logPath,
        startedAt,
        pid: started.pid,
        identity: recordedIdentity(started.pid),
        owner: { pid: state.self.pid, identity: state.self.identity },
        boot: bootId(),
      };
      saveRecord(task.record);
    }
    started.ended.then(
      ({ code, signal }) => finished(state, task, code, signal),
      (error: unknown) => finished(state, task, null, null, error instanceof Error ? error.message : String(error)),
    );
  } catch (error) {
    return `Error: could not start the command: ${error instanceof Error ? error.message : String(error)}`;
  }
  state.tasks.set(id, task);
  if (params.timeout !== undefined && params.timeout > 0) {
    task.timer = setTimeout(() => stop(task, "timeout"), params.timeout * 1000);
    task.timer.unref();
  }
  report(state);
  showWidget(state, task.sessionId);
  return `Started background task ${id} (${task.label}). Its result arrives as a message when it exits, so don't poll or wait for it. Output: ${task.logPath}`;
}

function stopFromTool(state: Registry, id: string | undefined, sessionId: string): string {
  const task = id === undefined ? undefined : state.tasks.get(id);
  if (!task || task.sessionId !== sessionId) return `Error: no background task ${id ?? "(id required for stop)"}`;
  if (task.status !== "running") return `${task.id} already finished.`;
  stop(task, "agent");
  return `Stopped background task ${task.id}.`;
}

export default function pipkinBackground(pi: ExtensionAPI): void {
  const state = registry();

  const register = (): void => {
    pi.registerTool({
      name: TOOL_NAME,
      label: TOOL_LABEL,
      description: TOOL_DESCRIPTION,
      promptSnippet: PROMPT_SNIPPET,
      promptGuidelines: PROMPT_GUIDELINES,
      parameters: BackgroundParams,
      async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
        const sessionId = ctx.sessionManager.getSessionId();
        let text: string;
        if (params.action === "start") text = start(state, params, ctx);
        else if (params.action === "stop") text = stopFromTool(state, params.id, sessionId);
        else text = listText([...state.tasks.values()].filter((task) => task.sessionId === sessionId), Date.now());
        return { content: [{ type: "text", text }], details: undefined };
      },
    });

    pi.on("agent_start", () => {
      if (state.delivering === 0) return;
      state.delivering = 0;
      report(state);
    });

    pi.on("agent_settled", (event, ctx) => deliver(state, ctx.sessionManager.getSessionId(), event));

    // Finished tasks stay in the widget until the user's next prompt, so a person coming back sees what happened.
    pi.on("before_agent_start", (event, ctx) => {
      if (event.messageType !== "user") return undefined;
      const sessionId = ctx.sessionManager.getSessionId();
      const undelivered = new Set(state.undelivered.get(sessionId));
      for (const [id, task] of state.tasks) {
        if (task.sessionId === sessionId && task.status !== "running" && !undelivered.has(task)) state.tasks.delete(id);
      }
      settleRecords(sessionId, ctx);
      showWidget(state, sessionId);
      return undefined;
    });

    pi.on("session_shutdown", (event, ctx) => {
      const sessionId = ctx.sessionManager.getSessionId();
      // The reload's next load takes the session over at its session_start; until then, results wait.
      if (event.reason === "reload") state.bindings.delete(sessionId);
      else if (event.reason === "quit") {
        for (const id of new Set([...state.bindings.keys(), sessionId])) leaveSession(state, id, "quit");
      } else leaveSession(state, sessionId, "switch");
      report(state);
    });
  };

  /**
   * Registers once every extension has loaded, and only when none of them has a `background` tool: Pi refuses to start
   * when two extensions register one tool name at load, so one the user installed wins instead.
   */
  let owned: boolean | undefined;
  pi.on("session_start", (_event, ctx) => {
    if (owned === undefined) {
      owned = !pi.getAllTools().some((tool) => tool.name === TOOL_NAME);
      if (owned) register();
    }
    if (!owned) return;
    const sessionId = ctx.sessionManager.getSessionId();
    const binding = { pi, ctx };
    state.bindings.set(sessionId, binding);
    skipUsedIds(state, sessionId, ctx);
    showWidget(state, sessionId);
    deliver(state, sessionId);
    reconcile(state, sessionId, binding).catch(() => undefined);
  });
}
