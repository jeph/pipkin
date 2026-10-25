/**
 * One `subagent` call: a child `AgentSession` in this Pi process, whose tools are stand-ins that run the parent's
 * tools through `ctx.executeTool()`, so every call the child makes passes the parent's `tool_call` and `tool_result`
 * handlers, the user's permission extensions among them, and asks in the parent's UI.
 */
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import type { ImageContent, TextContent, Usage } from "@earendil-works/pi-ai";
import {
  type AgentSession,
  type AgentSessionEvent,
  type AgentToolResult,
  createAgentSession,
  DefaultResourceLoader,
  type ExtensionToolContext,
  getAgentDir,
  ModelRuntime,
  SessionManager,
  SettingsManager,
  type ToolDefinition,
  type ToolInfo,
} from "@earendil-works/pi-coding-agent";
import type { AgentDefinition, ThinkingLevel } from "./agents.js";
import {
  MAX_RUNNING,
  type Registry,
  report,
  reportSoon,
  type Run,
  type RunStatus,
  runningCount,
  wakeQueued,
} from "./state.js";

/** The custom entry in a subagent's transcript linking it to the parent's call. */
export const ENTRY_TYPE = "pipkin-subagent";

/** What a result carries of the subagent's reply; the transcript keeps all of it. */
export const RESULT_LIMIT_BYTES = 50 * 1024;

/** How much of a tool call's main argument the activity line shows. */
const ACTIVITY_CHARS = 80;

export interface SubagentEntry {
  agent: string;
  description: string;
  task: string;
  toolCallId: string;
}

export interface SubagentUsage {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  totalTokens: number;
  cost: number;
}

/**
 * Marks every result's `details` as Pipkin subagents', a call that never became a run included, so the app can tell
 * them from those of another extension's `subagent` tool, once installed in its place.
 */
export const DETAILS_EXTENSION = "pipkin-subagents";

export interface SubagentDetails {
  extension: typeof DETAILS_EXTENSION;
  id: string;
  agent: string;
  description: string;
  /** Left out when the run ended before Pi wrote its transcript, as when the model had no API key. */
  sessionFile?: string | null;
  status: RunStatus;
  /** When the run left the queue; null for one stopped while queued, which never started. */
  startedAt: number | null;
  usage: SubagentUsage;
}

/** A call refused before it became a run, as for an unknown agent or model: no run, no transcript. */
export interface StartFailureDetails {
  extension: typeof DETAILS_EXTENSION;
  agent: string;
  description: string;
  status: "failed";
}

/**
 * The error result of a call that failed before it became a run. Returned rather than thrown, which Pi would turn
 * into the same error text but with empty details, so the result still says whose it is.
 */
export function startFailure(
  params: { agent: string; description: string },
  error: unknown,
): AgentToolResult<StartFailureDetails> {
  return {
    content: [text(error instanceof Error ? error.message : String(error))],
    details: { extension: DETAILS_EXTENSION, agent: params.agent, description: params.description, status: "failed" },
    isError: true,
  };
}

/** Everything a run needs, settled before it's added: a call naming no valid agent or model never becomes a run. */
export interface RunRequest {
  toolCallId: string;
  agent: AgentDefinition;
  task: string;
  description: string;
  model: NonNullable<ExtensionToolContext["model"]>;
  thinking: ThinkingLevel;
  toolNames: string[];
  /** The parent's tools as `pi.getAllTools()` reports them, for their prompt guidelines. */
  toolInfo: readonly ToolInfo[];
}

/** Runs [request] as a new run of [ctx]'s session, waiting for a slot first, and returns the tool's result. */
export async function runSubagent(
  state: Registry,
  request: RunRequest,
  signal: AbortSignal | undefined,
  ctx: ExtensionToolContext,
): Promise<AgentToolResult<SubagentDetails>> {
  const run: Run = {
    id: `r${state.nextId++}`,
    toolCallId: request.toolCallId,
    agent: request.agent.name,
    description: request.description,
    status: "queued",
    startedAt: null,
    endedAt: null,
    turn: 0,
    tokens: 0,
    lastActivity: null,
    error: null,
    sessionFile: null,
    messages: 0,
    inFlight: 0,
    steering: [],
    sessionId: ctx.sessionManager.getSessionId(),
    controller: new AbortController(),
    stoppedByUser: false,
    child: undefined,
    wake: undefined,
  };
  state.runs.set(run.id, run);
  const stop = signal ? AbortSignal.any([signal, run.controller.signal]) : run.controller.signal;
  const usage = emptyUsage();
  let reply = "";
  try {
    await slot(state, run, stop);
    run.startedAt = Date.now();
    report(state);
    reply = await converse(state, run, request, stop, usage, ctx);
  } catch (error) {
    if (!stop.aborted) run.error = error instanceof Error ? error.message : String(error);
  }
  return finish(state, run, stop, reply, usage);
}

/** Waits until the run may start: at once while fewer than MAX_RUNNING of its session's run, else when woken. */
async function slot(state: Registry, run: Run, stop: AbortSignal): Promise<void> {
  if (runningCount(state, run.sessionId) < MAX_RUNNING) {
    run.status = "running";
    return;
  }
  report(state);
  const { promise, resolve, reject } = Promise.withResolvers<void>();
  const onAbort = (): void => reject(stop.reason);
  stop.addEventListener("abort", onAbort, { once: true });
  run.wake = () => {
    stop.removeEventListener("abort", onAbort);
    resolve();
  };
  await promise;
}

/** The child's conversation: created, prompted with the task, disposed. Returns its final reply. */
async function converse(
  state: Registry,
  run: Run,
  request: RunRequest,
  stop: AbortSignal,
  usage: Usage,
  ctx: ExtensionToolContext,
): Promise<string> {
  const child = await createChild(state, run, request, ctx);
  run.child = child;
  run.sessionFile = child.sessionManager.getSessionFile() ?? null;
  report(state);
  const onAbort = (): void => void child.abort();
  stop.addEventListener("abort", onAbort, { once: true });
  const unsubscribe = child.subscribe((event) => {
    // Pi resets a run's abort state as it starts, so a Stop landing between `prompt()` and here would be lost.
    if (event.type === "agent_start" && stop.aborted) void child.abort();
    track(state, run, child, event, usage);
  });
  try {
    stop.throwIfAborted();
    await child.prompt(request.task);
    const failure = lastAssistant(child);
    if (failure?.stopReason === "error" && !stop.aborted) {
      throw new Error(failure.errorMessage ?? "The subagent's model request failed");
    }
    return child.getLastAssistantText() ?? "";
  } finally {
    stop.removeEventListener("abort", onAbort);
    unsubscribe();
    run.child = undefined;
    child.dispose();
  }
}

async function createChild(
  state: Registry,
  run: Run,
  request: RunRequest,
  ctx: ExtensionToolContext,
): Promise<AgentSession> {
  // Pi keeps the runtime private; sharing it gives the child the providers extensions registered and the parent's
  // fresh credentials. A second runtime would miss both, so its absence is an error rather than a fallback.
  const registry: object = ctx.modelRegistry;
  const modelRuntime = "runtime" in registry ? registry.runtime : undefined;
  if (!(modelRuntime instanceof ModelRuntime)) {
    throw new Error("Pipkin subagents can't reach Pi's model runtime: this Pi version isn't supported");
  }
  const cwd = ctx.cwd;
  const agentDir = getAgentDir();
  const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: ctx.isProjectTrusted() });
  const prompt = request.agent.prompt;
  const resourceLoader = new DefaultResourceLoader({
    cwd,
    agentDir,
    settingsManager,
    noExtensions: true,
    ...(prompt ? { appendSystemPromptOverride: (base: string[]) => [...base, prompt] } : {}),
  });
  await resourceLoader.reload();
  const { session } = await createAgentSession({
    cwd,
    agentDir,
    model: request.model,
    modelRuntime,
    thinkingLevel: request.thinking,
    noTools: "all",
    tools: request.toolNames,
    customTools: request.toolNames.map((name) => standIn(state, run, name, request.toolInfo, ctx)),
    resourceLoader,
    settingsManager,
    sessionManager: childSessionManager(ctx, cwd),
  });
  const entry: SubagentEntry = {
    agent: run.agent,
    description: run.description,
    task: request.task,
    toolCallId: run.toolCallId,
  };
  session.sessionManager.appendCustomEntry(ENTRY_TYPE, entry);
  return session;
}

/** `subagents/<parent id>/` beside the parent's file, or memory when the parent has no file either. */
function childSessionManager(ctx: ExtensionToolContext, cwd: string): SessionManager {
  const parentFile = ctx.sessionManager.getSessionFile();
  if (!parentFile) return SessionManager.inMemory(cwd);
  const dir = join(dirname(parentFile), "subagents", ctx.sessionManager.getSessionId());
  return SessionManager.create(cwd, dir, { parentSession: parentFile });
}

/**
 * The child's [name] tool: the parent's, run through the parent's `executeTool()`. A failure the parent reports,
 * a refusal included, is thrown so the child's model sees it as an error and carries on.
 */
function standIn(
  state: Registry,
  run: Run,
  name: string,
  toolInfo: readonly ToolInfo[],
  ctx: ExtensionToolContext,
): ToolDefinition {
  const tool = ctx.tools.find((candidate) => candidate.name === name);
  if (!tool) throw new Error(`The parent has no tool ${name}`);
  const guidelines = toolInfo.find((info) => info.name === name)?.promptGuidelines;
  return {
    name,
    label: tool.label,
    description: tool.description,
    // Pi lists custom tools in the system prompt only with a snippet; the description's first sentence serves.
    promptSnippet: firstSentence(tool.description),
    ...(guidelines ? { promptGuidelines: guidelines } : {}),
    parameters: tool.parameters,
    ...(tool.prepareArguments ? { prepareArguments: tool.prepareArguments } : {}),
    async execute(_toolCallId, params, signal, onUpdate) {
      run.inFlight++;
      report(state);
      const call = ctx.executeTool(name, params, { ...(signal ? { signal } : {}), ...(onUpdate ? { onUpdate } : {}) });
      const outcome = await untilAborted(call, signal).finally(() => {
        run.inFlight--;
        report(state);
      });
      if (outcome.isError) throw new Error(resultText(outcome.result.content) || `${name} failed`);
      return outcome.result;
    },
  };
}

/**
 * [work], or a rejection as soon as [signal] aborts: a nested call can wait on a permission question that Stop
 * doesn't answer, and the run must end anyway.
 */
function untilAborted<T>(work: Promise<T>, signal: AbortSignal | undefined): Promise<T> {
  if (!signal) return work;
  if (signal.aborted) return Promise.reject(signal.reason);
  const { promise, resolve, reject } = Promise.withResolvers<T>();
  const onAbort = (): void => reject(signal.reason);
  signal.addEventListener("abort", onAbort, { once: true });
  work.then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
  return promise;
}

/**
 * Follows the child's events into the run: turns, tokens, the latest step, the transcript's messages and Pi's queue
 * of steers not read yet, which Pi empties of each one as the child reads it.
 */
function track(state: Registry, run: Run, child: AgentSession, event: AgentSessionEvent, usage: Usage): void {
  switch (event.type) {
    case "turn_start":
      run.turn++;
      run.lastActivity = "thinking";
      reportSoon(state);
      return;
    case "tool_execution_start":
      run.lastActivity = activity(event.toolName, event.args);
      reportSoon(state);
      return;
    case "message_end":
      if (event.message.role === "assistant") {
        addUsage(usage, event.message.usage);
        run.tokens = usage.totalTokens;
      }
      // Pi saves the message right after its listeners return, and the app reads the file when the count changes.
      queueMicrotask(() => {
        const messages = child.sessionManager.getEntries().filter((entry) => entry.type === "message").length;
        if (messages === run.messages) {
          reportSoon(state);
          return;
        }
        run.messages = messages;
        report(state);
      });
      return;
    case "queue_update":
      run.steering = [...event.steering];
      report(state);
      return;
    default:
  }
}

/** A short line for a tool call: its name and main argument. */
export function activity(toolName: string, args: unknown): string {
  const fields = new Map<string, unknown>(typeof args === "object" && args !== null ? Object.entries(args) : []);
  const main = ["path", "command", "pattern", "query", "url", "file", "description"]
    .map((key) => fields.get(key))
    .find((value): value is string => typeof value === "string" && value.trim() !== "");
  if (main === undefined) return toolName;
  const line = main.replaceAll(/\s+/g, " ").trim();
  const cut = Array.from(line);
  return `${toolName} ${cut.length > ACTIVITY_CHARS ? `${cut.slice(0, ACTIVITY_CHARS - 1).join("")}…` : line}`;
}

function finish(
  state: Registry,
  run: Run,
  stop: AbortSignal,
  reply: string,
  usage: Usage,
): AgentToolResult<SubagentDetails> {
  run.wake = undefined;
  run.status = stop.aborted ? "stopped" : run.error !== null ? "failed" : "done";
  run.endedAt = Date.now();
  run.inFlight = 0;
  // Steers the subagent never read end with it.
  run.steering = [];
  run.lastActivity = null;
  // Pi writes a transcript only once it holds the task, so a run failing first, as for a model without an API key,
  // names a file that never appeared; the app then shows the run's error rather than a missing transcript.
  if (run.sessionFile && !existsSync(run.sessionFile)) delete run.sessionFile;
  report(state);
  wakeQueued(state, run.sessionId);
  const details: SubagentDetails = {
    extension: DETAILS_EXTENSION,
    id: run.id,
    agent: run.agent,
    description: run.description,
    ...(run.sessionFile === undefined ? {} : { sessionFile: run.sessionFile }),
    status: run.status,
    startedAt: run.startedAt,
    usage: {
      input: usage.input,
      output: usage.output,
      cacheRead: usage.cacheRead,
      cacheWrite: usage.cacheWrite,
      totalTokens: usage.totalTokens,
      cost: usage.cost.total,
    },
  };
  // The child's usage counts toward the chat's totals through the result, not toward its context.
  const result = { details, usage };
  if (run.status === "failed") {
    return { ...result, content: [text(`The subagent failed: ${run.error ?? "unknown error"}`)], isError: true };
  }
  if (run.status === "stopped") return { ...result, content: [text(stoppedText(run, reply))] };
  return { ...result, content: [text(capped(reply || "(The subagent finished without a reply.)", run.sessionFile))] };
}

function stoppedText(run: Run, reply: string): string {
  const who = run.stoppedByUser ? "The user stopped the subagent" : "The subagent was stopped";
  const started = run.startedAt === null ? " before it started." : " before it finished.";
  if (!reply) return who + started;
  return `${who}${started} Its last reply:\n\n${capped(reply, run.sessionFile)}`;
}

/** [reply] cut at RESULT_LIMIT_BYTES of UTF-8, on a character boundary, with where the rest is. */
export function capped(reply: string, sessionFile: string | null | undefined): string {
  const bytes = Buffer.from(reply, "utf8");
  if (bytes.length <= RESULT_LIMIT_BYTES) return reply;
  let end = RESULT_LIMIT_BYTES;
  // Back up to the start of a character: continuation bytes are 0b10xxxxxx.
  while (end > 0 && ((bytes[end] ?? 0) & 0xc0) === 0x80) end--;
  const kept = bytes.subarray(0, end).toString("utf8");
  const where = sessionFile
    ? `The full reply is in the subagent's transcript: ${sessionFile}`
    : "The subagent's transcript wasn't saved, since this chat isn't";
  return `${kept}\n\n[Cut at 50 KB of ${Math.ceil(bytes.length / 1024)} KB. ${where}]`;
}

function lastAssistant(child: AgentSession): { stopReason?: string; errorMessage?: string } | undefined {
  for (let index = child.messages.length - 1; index >= 0; index--) {
    const message = child.messages[index];
    if (message?.role === "assistant") return message;
  }
  return undefined;
}

function text(value: string): TextContent {
  return { type: "text", text: value };
}

function resultText(content: readonly (TextContent | ImageContent)[]): string {
  return content
    .filter((block): block is TextContent => block.type === "text")
    .map((block) => block.text)
    .join("\n");
}

function firstSentence(description: string): string {
  const line = description.split("\n")[0] ?? "";
  const end = line.search(/\.(\s|$)/);
  return end === -1 ? line : line.slice(0, end + 1);
}

function emptyUsage(): Usage {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 0,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
  };
}

function addUsage(total: Usage, add: Usage): void {
  total.input += add.input;
  total.output += add.output;
  total.cacheRead += add.cacheRead;
  total.cacheWrite += add.cacheWrite;
  total.totalTokens += add.totalTokens;
  total.cost.input += add.cost.input;
  total.cost.output += add.cost.output;
  total.cost.cacheRead += add.cost.cacheRead;
  total.cost.cacheWrite += add.cost.cacheWrite;
  total.cost.total += add.cost.total;
}
