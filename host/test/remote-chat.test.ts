/**
 * What else a remote chat runs on the remote besides Pi's tools: background tasks, subagents' tools and attachments.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fauxAssistantMessage, fauxToolCall, type Message, type ToolCall } from "@earendil-works/pi-ai";
import type { AgentSession, ExtensionFactory } from "@earendil-works/pi-coding-agent";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { isObject } from "../src/framing.js";
import pipkinBackground from "../src/pi-extensions/pipkin-background/index.js";
import { RESULT_TYPE } from "../src/pi-extensions/pipkin-background/tasks.js";
import { createPipkinRemote } from "../src/pi-extensions/pipkin-remote/index.js";
import { remoteSystemAppend } from "../src/prompt.js";
import { quote } from "../src/remote/script.js";
import { runOp } from "./harness.js";
import { alive, eventually, type RemoteFixture, startRemoteFixture } from "./remote-harness.js";
import { sshdUnavailable } from "./sshd.js";
import {
  fakeUi,
  installChannel,
  makeParent,
  messageText,
  pipkinSubagents,
  removeChannel,
  type Script,
  subagentResults,
  type Turn,
} from "./subagents-harness.js";

const unavailable = await sshdUnavailable();
const CHANNEL = Symbol.for("pipkin.channel");
const SHARED = [Symbol.for("pipkin.remote"), Symbol.for("pipkin.background")];

type ToolResult = Extract<Message, { role: "toolResult" }>;

/** The pid a task's command wrote to [file], once it has. */
async function pidIn(file: string): Promise<number> {
  await eventually(async () => (await readFile(file, "utf8").catch(() => "")).endsWith("\n"), 10_000);
  return Number((await readFile(file, "utf8")).trim());
}

describe.skipIf(unavailable !== null)(`a remote chat against a throwaway sshd${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let fixture: RemoteFixture;
  let session: AgentSession;
  /** What the app sent on the `background` topic goes to these. */
  let backgroundHandlers: ((data: unknown) => void)[];
  let turns: Turn[];
  const environment = { PI_CODING_AGENT_DIR: process.env.PI_CODING_AGENT_DIR, PREFIX: process.env.PREFIX };

  /**
   * The parent makes the JSON tool call each prompt holds, then is done; a subagent, whose task starts with CHILD,
   * runs the bash command after it.
   */
  const script: Script = (turn) => {
    turns.push(turn);
    if (turn.step > 0) return fauxAssistantMessage(turn.who === "child" ? `child got: ${messageText(turn.lastToolResult)}` : "done");
    if (turn.who === "child") return fauxAssistantMessage(fauxToolCall("bash", { command: turn.first.slice("CHILD ".length) }), { stopReason: "toolUse" });
    if (!turn.latest.startsWith("{")) return fauxAssistantMessage("noted");
    const { tool, args } = JSON.parse(turn.latest) as { tool: string; args: ToolCall["arguments"] };
    return fauxAssistantMessage(fauxToolCall(tool, args), { stopReason: "toolUse" });
  };

  async function call(tool: string, args: ToolCall["arguments"]): Promise<ToolResult> {
    await session.prompt(JSON.stringify({ tool, args }));
    const result = session.messages.findLast((message): message is ToolResult => message.role === "toolResult");
    if (result === undefined) throw new Error("No tool result");
    return result;
  }

  async function start(extensions: ExtensionFactory[], tools: string[]): Promise<void> {
    const fake = fakeUi(installChannel());
    // The background extension draws a widget with the theme, which the fake UI lacks. Pi copies the UI's own
    // properties, so its proxy's fallback doesn't reach the extension: these have to be real, enumerable ones.
    Object.assign(fake.context, { theme: { fg: (_color: string, text: string) => text }, setWidget: () => undefined });
    const ui = fake;
    // In place of installChannel's, which records only the subagents topic, and before the extensions load: the
    // background registry keeps the channel it finds then.
    (globalThis as Record<symbol, unknown>)[CHANNEL] = {
      send: () => undefined,
      on: (topic: string, handler: (data: unknown) => void) => {
        if (topic === "background") backgroundHandlers.push(handler);
        return () => undefined;
      },
    };
    const dir = { cwd: fixture.standIn, agentDir: fixture.dirs.configDir, sessionsDir: path.join(fixture.dirs.configDir, "sessions") };
    ({ session } = await makeParent(dir, script, { extensions, tools, ui }));
  }

  beforeAll(async () => {
    fixture = await startRemoteFixture();
    process.env.PI_CODING_AGENT_DIR = fixture.dirs.configDir;
    process.env.PREFIX = fixture.dirs.prefix;
  });

  afterAll(async () => {
    for (const [key, value] of Object.entries(environment)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    await fixture.stop();
  });

  beforeEach(() => {
    turns = [];
    backgroundHandlers = [];
    const global = globalThis as Record<symbol, unknown>;
    for (const key of SHARED) delete global[key];
  });

  afterEach(async () => {
    // As Pi ends a session: `session_shutdown` first, which stops the session's background tasks and drops the
    // extension's hold on its context, and only then `dispose`, which makes that context stale.
    await session.extensionRunner.emit({ type: "session_shutdown", reason: "quit" });
    session.dispose();
    // The stopped tasks' ssh exits a moment later; the next test starts once every one has.
    const background = (globalThis as Record<symbol, { tasks: Map<string, { status: string }> } | undefined>)[Symbol.for("pipkin.background")];
    await eventually(() => ![...(background?.tasks.values() ?? [])].some((task) => task.status === "running"), 10_000);
    removeChannel();
    const global = globalThis as Record<symbol, unknown>;
    for (const key of SHARED) delete global[key];
  });

  describe("background tasks", () => {
    beforeEach(async () => {
      await start([createPipkinRemote(fixture.standIn), pipkinBackground], ["read", "bash", "background"]);
    });

    it("run on the remote, in its folder, and bring their output back to the local log", async () => {
      const started = await call("background", { action: "start", command: "pwd; echo done-remote", label: "where" });
      const log = /Output: (.+)$/.exec(messageText(started))?.[1];
      expect(log).toBeDefined();
      await eventually(() => session.messages.some((message) => message.role === "custom" && message.customType === RESULT_TYPE), 10_000);
      expect(await readFile(log ?? "", "utf8")).toBe(`${fixture.remoteDir}\ndone-remote\n`);
      const result = session.messages.find((message) => message.role === "custom" && message.customType === RESULT_TYPE);
      expect(isObject(result) && JSON.stringify(result.content)).toContain("exit 0");
      // The result points the model at the local log, which the remote read serves from the phone.
      const read = await call("read", { path: log ?? "" });
      expect(messageText(read)).toContain("done-remote");
    });

    it("outlive the turn, and end on the remote when the agent stops them", async () => {
      const pidFile = path.join(fixture.dirs.base, "agent-stop.pid");
      await call("background", { action: "start", command: `sleep 619 & echo $! > ${quote(pidFile)}; wait` });
      const pid = await pidIn(pidFile);
      expect(alive(pid)).toBe(true);
      expect(messageText(await call("background", { action: "list" }))).toContain("running");
      expect(messageText(await call("background", { action: "stop", id: "bg1" }))).toBe("Stopped background task bg1.");
      await eventually(() => !alive(pid), 10_000);
    });

    it("end on the remote when the user stops them", async () => {
      const pidFile = path.join(fixture.dirs.base, "user-stop.pid");
      await call("background", { action: "start", command: `sleep 620 & echo $! > ${quote(pidFile)}; wait`, cwd: "~" });
      const pid = await pidIn(pidFile);
      expect(alive(pid)).toBe(true);
      for (const handler of backgroundHandlers) handler({ action: "stop" });
      await eventually(() => !alive(pid), 10_000);
      await eventually(() => session.messages.some((message) => message.role === "custom" && message.customType === RESULT_TYPE), 10_000);
      const result = session.messages.find((message) => message.role === "custom" && message.customType === RESULT_TYPE);
      expect(isObject(result) && JSON.stringify(result.content)).toContain("stopped by the user");
    });

    it("start in a folder relative to the remote folder, or in the remote home for ~", async () => {
      await call("background", { action: "start", command: "pwd", cwd: "~" });
      await eventually(() => session.messages.some((message) => message.role === "custom" && message.customType === RESULT_TYPE), 10_000);
      const result = session.messages.find((message) => message.role === "custom" && message.customType === RESULT_TYPE);
      const details = isObject(result) && isObject(result.details) && Array.isArray(result.details.tasks) ? result.details.tasks[0] : undefined;
      const log = isObject(details) && typeof details.logPath === "string" ? details.logPath : "";
      expect(await readFile(log, "utf8")).toBe(`${fixture.host.home ?? ""}\n`);
    });
  });

  describe("subagents", () => {
    beforeEach(async () => {
      await start([createPipkinRemote(fixture.standIn), pipkinSubagents], ["read", "bash", "subagent"]);
    });

    it("run their tools on the remote and are told where it is, with the remote folder as their working directory", async () => {
      const result = await call("subagent", { agent: "General", task: "CHILD pwd", description: "where" });
      expect(messageText(result)).toBe(`child got: ${fixture.remoteDir}`);
      const child = turns.find((turn) => turn.who === "child");
      expect(child?.systemPrompt).toContain(remoteSystemAppend(fixture.host, fixture.remoteDir));
      expect(child?.systemPrompt).toContain(`<cwd>\n${fixture.remoteDir}\n</cwd>`);
      expect(child?.systemPrompt).not.toContain(fixture.standIn);
      expect(subagentResults(session).size).toBe(1);
    });
  });

  describe("attachments", () => {
    beforeEach(async () => {
      await start([], ["read"]);
    });

    it("go to the remote's cache folder, and the model gets their remote paths", async () => {
      const files = [
        { name: "notes.txt", data: Buffer.from("hello remote").toString("base64") },
        { name: "notes.txt", data: Buffer.from("second").toString("base64") },
      ];
      const result = await runOp("save_attachments", fixture.dirs, { files, cwd: fixture.standIn });
      expect(result).toMatchObject({ ok: true });
      const paths = isObject(result.data) && Array.isArray(result.data.paths) ? result.data.paths : [];
      expect(paths).toHaveLength(2);
      const [first, second] = paths.map(String);
      expect(path.dirname(path.dirname(first ?? ""))).toBe(path.join(fixture.remoteCache, "pipkin", "attachments"));
      expect(path.basename(second ?? "")).toBe("notes-2.txt");
      expect(await readFile(first ?? "", "utf8")).toBe("hello remote");
      expect(await readFile(second ?? "", "utf8")).toBe("second");
    });

    it("stay on the phone for a local chat", async () => {
      const files = [{ name: "a.txt", data: Buffer.from("local").toString("base64") }];
      const result = await runOp("save_attachments", fixture.dirs, { files, cwd: fixture.dirs.home });
      const paths = isObject(result.data) && Array.isArray(result.data.paths) ? result.data.paths.map(String) : [];
      expect(path.dirname(path.dirname(paths[0] ?? ""))).toBe(path.join(fixture.dirs.root, "attachments"));
    });
  });
});
