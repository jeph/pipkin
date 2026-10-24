/**
 * The runs one Pi process knows about, shared by every load of the extension (Pi loads it again for each session and
 * `/reload`), and the `runs` messages that tell the app about them over Pipkin's channel.
 */
import type { AgentSession } from "@earendil-works/pi-coding-agent";
import { isObject } from "../../framing.js";
import { type PipkinChannel, pipkinChannel } from "../pipkin-channel.js";

/** The channel topic: `runs` lists go to the app; `steer`, `stop` and `stopAll` come back. */
export const TOPIC = "subagents";
export const PROTOCOL_VERSION = 1;

/** Subagents running at once per parent session; further calls wait their turn. */
export const MAX_RUNNING = 4;

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
  sessionFile: string | null;
  messages: number;
  /** The run's tool calls handed to the parent's `executeTool()` that haven't returned or thrown. */
  inFlight: number;
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
}

export interface Registry {
  channel: PipkinChannel;
  nextId: number;
  /** Every run not yet dropped, in the order they were called. */
  runs: Map<string, Run>;
  /** The session Pi shows the app, whose runs the `runs` messages list. */
  current: string | undefined;
  timer: NodeJS.Timeout | undefined;
  lastSentAt: number;
  lastSent: string | undefined;
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
    timer: undefined,
    lastSentAt: 0,
    lastSent: undefined,
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
  const run = typeof data.id === "string" ? state.runs.get(data.id) : undefined;
  if (!run) return;
  if (data.action === "stop") stopRun(run);
  else if (data.action === "steer" && typeof data.text === "string" && data.text.trim() !== "") steer(run, data.text);
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
    sessionFile: run.sessionFile,
    messages: run.messages,
    inFlight: run.inFlight,
  };
}

/**
 * Sends the current session's runs now. [force] sends even an unchanged list, as after `session_start`, when the app
 * may have missed the last one.
 */
export function report(state: Registry, force = false): void {
  clearTimeout(state.timer);
  state.timer = undefined;
  const runs = [...state.runs.values()].filter((run) => run.sessionId === state.current).map(view);
  const payload = JSON.stringify(runs);
  if (!force && payload === state.lastSent) return;
  state.lastSent = payload;
  state.lastSentAt = Date.now();
  state.channel.send(TOPIC, { version: PROTOCOL_VERSION, runs });
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
  for (const [id, run] of state.runs) if (run.sessionId === sessionId && !isActive(run)) state.runs.delete(id);
}
