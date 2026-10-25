/**
 * The runs one Pi process knows about, shared by every load of the extension (Pi loads it again for each session and
 * `/reload`), and the messages that tell the app about them over Pipkin's channel: `runs` lists, and the transcript of
 * each run a subagent screen follows live (`./live.ts`).
 */
import type { AgentSession, AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import { isObject } from "../../framing.js";
import { type PipkinChannel, pipkinChannel } from "../pipkin-channel.js";
import { append, forApp, type LiveLog } from "./live.js";

/**
 * The channel topic. To the app: `runs` lists, a followed run's `live` log, then each of its `event`s, and the steers
 * `unsteered` from a run. From the app: `steer`, `unsteer`, `stop`, `stopAll`, and `follow` and `unfollow` a run.
 */
export const TOPIC = "subagents";
export const PROTOCOL_VERSION = 1;

/** Subagents running at once per parent session; further calls wait their turn. */
export const MAX_RUNNING = 6;

/** The shortest gap between two `runs` messages sent only for a turn, token or activity change. */
export const THROTTLE_MS = 250;

export type RunStatus = "queued" | "running" | "done" | "failed" | "stopped";

/** A run as the app sees it in a `runs` message. */
export interface RunView {
  id: string;
  toolCallId: string;
  agent: string;
  description: string;
  status: RunStatus;
  startedAt: number | null;
  endedAt: number | null;
  turn: number;
  tokens: number;
  lastActivity: string | null;
  error: string | null;
  /**
   * The run's transcript file; null while it runs in memory. Left out once the run ended before Pi wrote the file,
   * as Pi does with the task, so it names no file; a running run's may not exist yet.
   */
  sessionFile?: string | null;
  messages: number;
  /** The run's tool calls handed to the parent's `executeTool()` that haven't returned or thrown. */
  inFlight: number;
  /** The steers the app sent that the subagent hasn't read yet, oldest first, as Pi's steering queue holds them. */
  steering: string[];
}

export interface Run extends RunView {
  /** The parent session the run belongs to. */
  sessionId: string;
  /** Ends the run on the user's Stop; the parent's tool signal ends it too. */
  controller: AbortController;
  stoppedByUser: boolean;
  child: AgentSession | undefined;
  /** Starts the run once it's queued and a slot frees up. */
  wake: (() => void) | undefined;
  /** The child's events so far, for a screen that starts following the run; kept until the run is dropped. */
  log: LiveLog;
}

export interface Registry {
  channel: PipkinChannel;
  nextId: number;
  /** Every run not yet dropped, in the order they were called. */
  runs: Map<string, Run>;
  /** The session Pi shows the app, whose runs the `runs` messages list. */
  current: string | undefined;
  /**
   * Whether the current session's `subagent` tool is another extension's, Pipkin subagents standing aside: the app
   * then draws its calls as ordinary tool rows.
   */
  standingAside: boolean;
  timer: NodeJS.Timeout | undefined;
  lastSentAt: number;
  lastSent: string | undefined;
  /** The runs whose events go to the app as they come, while a subagent screen shows them. */
  followed: Set<string>;
}

const SHARED = Symbol.for("pipkin.subagents");

export function registry(): Registry {
  const global = globalThis as { [SHARED]?: Registry };
  if (global[SHARED]) return global[SHARED];
  const created: Registry = {
    channel: pipkinChannel(),
    nextId: 1,
    runs: new Map(),
    current: undefined,
    standingAside: false,
    timer: undefined,
    lastSentAt: 0,
    lastSent: undefined,
    followed: new Set(),
  };
  global[SHARED] = created;
  created.channel.on(TOPIC, (data) => fromApp(created, data));
  return created;
}

export function isActive(run: RunView): boolean {
  return run.status === "queued" || run.status === "running";
}

function fromApp(state: Registry, data: unknown): void {
  if (!isObject(data)) return;
  if (data.action === "stopAll") {
    for (const run of state.runs.values()) if (run.sessionId === state.current) stopRun(run);
    return;
  }
  if (typeof data.id !== "string") return;
  const run = state.runs.get(data.id);
  if (data.action === "unfollow") state.followed.delete(data.id);
  // The app waits for this answer, so a run that's gone gets one too.
  if (data.action === "unsteer") unsteer(state, data.id, run);
  if (!run) return;
  if (data.action === "stop") stopRun(run);
  else if (data.action === "steer" && typeof data.text === "string" && data.text.trim() !== "") steer(run, data.text);
  else if (data.action === "follow") follow(state, run);
}

/** Sends [run]'s log so far, then each of its events as it comes until the app unfollows it. */
function follow(state: Registry, run: Run): void {
  state.followed.add(run.id);
  state.channel.send(TOPIC, { version: PROTOCOL_VERSION, live: { id: run.id, entries: run.log.entries } });
}

/** Keeps one of [run]'s child's events in its log, and sends it on while the app follows the run. */
export function recordEvent(state: Registry, run: Run, event: AgentSessionEvent): void {
  const forwarded = forApp(event);
  if (!forwarded) return;
  const entry = { at: Date.now(), event: forwarded };
  // Sent before the log may merge it into an earlier entry.
  if (state.followed.has(run.id)) {
    state.channel.send(TOPIC, { version: PROTOCOL_VERSION, event: { id: run.id, ...entry } });
  }
  append(run.log, entry);
}

/** The user's Stop: the run ends and its tool call says the user stopped it. */
export function stopRun(run: Run): void {
  if (!isActive(run) || run.controller.signal.aborted) return;
  run.stoppedByUser = true;
  run.controller.abort();
}

function steer(run: Run, text: string): void {
  if (run.status !== "running" || !run.child) return;
  // A steer arriving as the run ends has nothing left to reach; Pi refuses extension commands, which a subagent
  // without extensions can't run anyway.
  run.child.steer(text).catch(() => undefined);
}

/**
 * Takes back the steers run [id]'s subagent hasn't read yet and sends them to the app, which puts them back in the
 * steer box; none when the run isn't running. Pi may already have taken them for the subagent's next turn and still
 * delivers those, so the app leaves out the ones the subagent starts reading.
 */
function unsteer(state: Registry, id: string, run: Run | undefined): void {
  const steering = run?.status === "running" && run.child ? run.child.clearQueue().steering : [];
  state.channel.send(TOPIC, { version: PROTOCOL_VERSION, unsteered: { id, steering } });
}

export function view(run: Run): RunView {
  return {
    id: run.id,
    toolCallId: run.toolCallId,
    agent: run.agent,
    description: run.description,
    status: run.status,
    startedAt: run.startedAt,
    endedAt: run.endedAt,
    turn: run.turn,
    tokens: run.tokens,
    lastActivity: run.lastActivity,
    error: run.error,
    ...(run.sessionFile === undefined ? {} : { sessionFile: run.sessionFile }),
    messages: run.messages,
    inFlight: run.inFlight,
    steering: run.steering,
  };
}

/**
 * Sends the current session's runs now, and whether Pipkin subagents stands aside in it. [force] sends even an
 * unchanged list, as after `session_start`, when the app may have missed the last one.
 */
export function report(state: Registry, force = false): void {
  clearTimeout(state.timer);
  state.timer = undefined;
  const runs = [...state.runs.values()].filter((run) => run.sessionId === state.current).map(view);
  const message = { version: PROTOCOL_VERSION, standingAside: state.standingAside, runs };
  const payload = JSON.stringify(message);
  if (!force && payload === state.lastSent) return;
  state.lastSent = payload;
  state.lastSentAt = Date.now();
  state.channel.send(TOPIC, message);
}

/** Sends the runs at most every THROTTLE_MS, for changes the app only shows as they go: turns, tokens, activity. */
export function reportSoon(state: Registry): void {
  if (state.timer !== undefined) return;
  const wait = state.lastSentAt + THROTTLE_MS - Date.now();
  if (wait <= 0) {
    report(state);
    return;
  }
  state.timer = setTimeout(() => report(state), wait);
  // Pi exits once its stdin ends; a pending update mustn't keep it running.
  state.timer.unref();
}

export function runningCount(state: Registry, sessionId: string): number {
  let count = 0;
  for (const run of state.runs.values()) if (run.sessionId === sessionId && run.status === "running") count++;
  return count;
}

/** Starts the session's queued runs, oldest first, while slots are free. */
export function wakeQueued(state: Registry, sessionId: string): void {
  let free = MAX_RUNNING - runningCount(state, sessionId);
  for (const run of state.runs.values()) {
    if (free <= 0) return;
    if (run.sessionId !== sessionId || run.status !== "queued" || !run.wake) continue;
    run.status = "running";
    run.wake();
    run.wake = undefined;
    free--;
  }
}

/** Drops the session's finished runs, when the user sends the parent its next message. */
export function dropFinished(state: Registry, sessionId: string): void {
  for (const [id, run] of state.runs) {
    if (run.sessionId !== sessionId || isActive(run)) continue;
    state.runs.delete(id);
    state.followed.delete(id);
  }
}
