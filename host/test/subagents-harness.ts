/**
 * Real Pi SDK sessions for the Pipkin subagents tests: pi-ai's faux provider answers for the parent and its
 * subagents through a script, and a fake Pipkin channel records what the extension tells the app.
 */
import { mkdir, mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  type AssistantMessage,
  fauxAssistantMessage,
  type FauxProviderHandle,
  fauxProvider,
  getCurrentSystemPrompt,
  getCurrentTools,
  type Message,
} from "@earendil-works/pi-ai";
import {
  type AgentSession,
  createAgentSession,
  DefaultResourceLoader,
  type ExtensionFactory,
  type ExtensionUIContext,
  ModelRuntime,
  SessionManager,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";
import pipkinSubagents from "../src/pi-extensions/pipkin-subagents/index.js";
import type { RunView } from "../src/pi-extensions/pipkin-subagents/state.js";

const CHANNEL = Symbol.for("pipkin.channel");
const REGISTRY = Symbol.for("pipkin.subagents");

export { pipkinSubagents };

export interface RunsMessage {
  version: number;
  standingAside: boolean;
  runs: RunView[];
}

/**
 * A followed run's log so far, one of its events, or the steers taken back from a run, as the extension sends them on
 * `subagents`.
 */
export interface LiveMessage {
  version: number;
  live?: { id: string; entries: { at: number; event: Record<string, unknown> }[] };
  event?: { id: string; at: number; event: Record<string, unknown> };
  unsteered?: { id: string; steering: string[] };
}

/** Stands in for Pipkin's channel: records what the extension sends on `subagents` and passes on the app's messages. */
export interface FakeChannel {
  sent: RunsMessage[];
  /** The `live` logs and `event`s sent for followed runs, in order. */
  live: LiveMessage[];
  /** Called with each runs message as it's sent, before the extension carries on. */
  onSend: ((message: RunsMessage) => void) | undefined;
  /** The runs in the latest message. */
  latest(): RunView[];
  fromApp(data: unknown): void;
}

/**
 * Installs a fresh fake channel and forgets the extension's registry, as a new Pi process would. Call before loading
 * the extension.
 */
export function installChannel(): FakeChannel {
  const handlers: ((data: unknown) => void)[] = [];
  const channel: FakeChannel = {
    sent: [],
    live: [],
    onSend: undefined,
    latest: () => channel.sent.at(-1)?.runs ?? [],
    fromApp: (data) => {
      for (const handler of handlers) handler(data);
    },
  };
  const global = globalThis as Record<symbol, unknown>;
  delete global[REGISTRY];
  global[CHANNEL] = {
    send: (topic: string, data: unknown) => {
      if (topic !== "subagents") return;
      // A copy, since the runs keep changing after they're sent.
      const message = structuredClone(data) as RunsMessage | LiveMessage;
      if (!("runs" in message)) {
        channel.live.push(message);
        return;
      }
      channel.sent.push(message);
      channel.onSend?.(message);
    },
    on: (topic: string, handler: (data: unknown) => void) => {
      if (topic === "subagents") handlers.push(handler);
      return () => undefined;
    },
  };
  return channel;
}

export function removeChannel(): void {
  const global = globalThis as Record<symbol, unknown>;
  delete global[CHANNEL];
  delete global[REGISTRY];
}

/** One model request, as the script sees it. */
export interface Turn {
  /** `child` when the conversation's first user message starts with CHILD, else `parent`. */
  who: "parent" | "child";
  /** The conversation's first user message: the parent's prompt or the subagent's task. */
  first: string;
  /** The latest user message, such as a steer. */
  latest: string;
  /** Assistant messages since the latest user message. */
  step: number;
  messages: Message[];
  lastToolResult: Extract<Message, { role: "toolResult" }> | undefined;
  toolNames: string[];
  systemPrompt: string;
  /** The id of the model the request went to. */
  model: string;
  signal: AbortSignal | undefined;
}

export type Script = (turn: Turn) => AssistantMessage | Promise<AssistantMessage>;

export function messageText(message: Message | undefined): string {
  if (!message || message.role === "system") return "";
  if (typeof message.content === "string") return message.content;
  return message.content.map((block) => (block.type === "text" ? block.text : "")).join("");
}

/** A faux provider whose every request goes to [script]. */
export function scriptedProvider(script: Script): FauxProviderHandle {
  const faux = fauxProvider({ models: [{ id: "faux-1" }, { id: "faux-2" }] });
  const step = (
    context: { messages: Message[] },
    options: { signal?: AbortSignal } | undefined,
    _state: unknown,
    model: { id: string },
  ) => {
    faux.appendResponses([step]);
    const messages = context.messages;
    const users = messages.filter((message) => message.role === "user");
    const latestIndex = messages.findLastIndex((message) => message.role === "user");
    const first = messageText(users[0]);
    return script({
      who: first.startsWith("CHILD") ? "child" : "parent",
      first,
      latest: messageText(users.at(-1)),
      step: messages.slice(latestIndex + 1).filter((message) => message.role === "assistant").length,
      messages,
      lastToolResult: messages.findLast((message) => message.role === "toolResult"),
      toolNames: getCurrentTools(messages).map((tool) => tool.name),
      systemPrompt: getCurrentSystemPrompt(messages),
      model: model.id,
      signal: options?.signal,
    });
  };
  // The faux provider takes a step as a request arrives but runs it a tick later, so parallel requests need a deep
  // queue; each step adds one back.
  faux.setResponses(Array.from({ length: 256 }, () => step));
  return faux;
}

/** The text of a parent's reply after its tools ran: every tool result, so tests can read what came back. */
export function echoResults(turn: Turn): AssistantMessage {
  const results = turn.messages
    .slice(turn.messages.findLastIndex((message) => message.role === "user") + 1)
    .filter((message) => message.role === "toolResult")
    .map((message) => messageText(message));
  return fauxAssistantMessage(`parent done: ${results.join(" | ")}`);
}

export interface Dir {
  cwd: string;
  agentDir: string;
  sessionsDir: string;
}

export async function makeDir(): Promise<Dir> {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "pipkin-subagents-")));
  const cwd = path.join(base, "project");
  const agentDir = path.join(base, "agent");
  await mkdir(cwd, { recursive: true });
  await mkdir(agentDir, { recursive: true });
  await writeFile(path.join(cwd, "hello.txt"), "hello from the project\n");
  // The child reads Pi's config folder through getAgentDir(), as it does under Pipkin.
  process.env.PI_CODING_AGENT_DIR = agentDir;
  return { cwd, agentDir, sessionsDir: path.join(agentDir, "sessions", "--project--") };
}

export async function removeDir(dir: Dir): Promise<void> {
  delete process.env.PI_CODING_AGENT_DIR;
  await rm(path.dirname(dir.cwd), { recursive: true, force: true });
}

/** A dialog the fake UI was asked, with the runs the app had been told about at that moment. */
export interface Asked {
  kind: "confirm" | "select";
  title: string;
  message: string;
  runs: RunView[];
}

export interface FakeUi {
  context: ExtensionUIContext;
  asked: Asked[];
}

const noop = (): void => undefined;

/** A UI whose dialogs [answer]; each question records the runs [channel] last sent. */
export function fakeUi(
  channel: FakeChannel,
  answer: (asked: Asked) => boolean | string | undefined | Promise<boolean | string | undefined> = () => true,
): FakeUi {
  const asked: Asked[] = [];
  const ui: Record<string, unknown> = {
    async confirm(title: string, message: string) {
      const entry: Asked = { kind: "confirm", title, message, runs: channel.latest() };
      asked.push(entry);
      return (await answer(entry)) === true;
    },
    async select(title: string, options: string[]) {
      const entry: Asked = { kind: "select", title, message: options.join("|"), runs: channel.latest() };
      asked.push(entry);
      const chosen = await answer(entry);
      return typeof chosen === "string" ? chosen : undefined;
    },
    input: async () => undefined,
    editor: async () => undefined,
    custom: async () => undefined,
    notify: noop,
    onTerminalInput: () => noop,
    getEditorText: () => "",
    getAllThemes: () => [],
    getTheme: () => undefined,
    setTheme: () => ({ success: false }),
    getToolsExpanded: () => false,
  };
  const context = new Proxy(ui, { get: (target, key: string) => (key in target ? target[key] : noop) });
  return { context: context as unknown as ExtensionUIContext, asked };
}

export interface ParentOptions {
  extensions?: ExtensionFactory[];
  tools?: string[];
  /** Saves the parent's session to a file, so its subagents get transcripts. */
  persist?: boolean;
  projectTrusted?: boolean;
  ui: FakeUi;
  /** Extension files Pi loads itself, as it does those in its `extensions/` folder. */
  extensionPaths?: string[];
}

export interface Parent {
  session: AgentSession;
  faux: FauxProviderHandle;
  runtime: ModelRuntime;
}

/** A parent session with Pipkin subagents loaded, bound to [options.ui] as Pipkin's RPC mode would be. */
export async function makeParent(dir: Dir, script: Script, options: ParentOptions): Promise<Parent> {
  const faux = scriptedProvider(script);
  const runtime = await ModelRuntime.create({
    authPath: path.join(dir.agentDir, "auth.json"),
    modelsPath: null,
    refreshOnCreate: false,
  });
  runtime.registerNativeProvider(faux.provider);
  const settingsManager = SettingsManager.inMemory({}, { projectTrusted: options.projectTrusted ?? false });
  const resourceLoader = new DefaultResourceLoader({
    cwd: dir.cwd,
    agentDir: dir.agentDir,
    settingsManager,
    noExtensions: true,
    noSkills: true,
    noPromptTemplates: true,
    noThemes: true,
    noContextFiles: true,
    extensionFactories: options.extensions ?? [pipkinSubagents],
    additionalExtensionPaths: options.extensionPaths ?? [],
  });
  await resourceLoader.reload();
  const { session } = await createAgentSession({
    cwd: dir.cwd,
    agentDir: dir.agentDir,
    model: faux.getModel(),
    modelRuntime: runtime,
    thinkingLevel: "off",
    tools: options.tools ?? ["read", "bash", "subagent"],
    resourceLoader,
    settingsManager,
    sessionManager: options.persist ? SessionManager.create(dir.cwd, dir.sessionsDir) : SessionManager.inMemory(dir.cwd),
  });
  await session.bindExtensions({ uiContext: options.ui.context, mode: "rpc" });
  return { session, faux, runtime };
}

/** Polls [check] until it holds, for up to [ms]. */
export async function eventually(check: () => boolean, ms = 5000): Promise<void> {
  const until = Date.now() + ms;
  while (!check()) {
    if (Date.now() > until) throw new Error("timed out waiting");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

/** The `subagent` tool result messages in [session], by tool call id. */
export function subagentResults(session: AgentSession): Map<string, Extract<Message, { role: "toolResult" }>> {
  const results = new Map<string, Extract<Message, { role: "toolResult" }>>();
  for (const message of session.messages) {
    if (message.role === "toolResult" && message.toolName === "subagent") results.set(message.toolCallId, message);
  }
  return results;
}
