/**
 * The emulator test fixtures in `fixtures/emulator/`, loaded by a real Pi from their files the way Pi loads them from
 * `~/.pipkin-dev/pi/extensions/`, with Pipkin subagents.
 */
import { readFileSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fauxAssistantMessage } from "@earendil-works/pi-ai";
import type { AgentSession, ExtensionFactory } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  type Dir,
  type FakeChannel,
  type FakeUi,
  fakeUi,
  installChannel,
  makeDir,
  makeParent,
  messageText,
  pipkinSubagents,
  removeChannel,
  removeDir,
  subagentResults,
} from "./subagents-harness.js";

const FIXTURES = path.join(import.meta.dirname, "fixtures/emulator");
const MODEL = path.join(FIXTURES, "pipkin-test-model/index.ts");
const GATE = path.join(FIXTURES, "pipkin-test-gate/index.ts");

let dir: Dir;
let channel: FakeChannel;

beforeEach(async () => {
  dir = await makeDir();
  process.env.PIPKIN_TEST_MODEL_FAST = "1";
  channel = installChannel();
});

afterEach(async () => {
  removeChannel();
  delete process.env.PIPKIN_TEST_GATE_SUBAGENT;
  delete process.env.PIPKIN_TEST_MODEL_FAST;
  await removeDir(dir);
});

/** An `ask_user` tool like a question extension's, which asks through the UI. */
const askUser: ExtensionFactory = (pi) => {
  pi.registerTool({
    name: "ask_user",
    label: "Ask user",
    description: "Ask the user a question.",
    parameters: Type.Object({ question: Type.String(), options: Type.Array(Type.String()) }),
    async execute(_id, params, _signal, _onUpdate, ctx) {
      const answer = await ctx.ui.select(params.question, params.options);
      return { content: [{ type: "text", text: `The user picked ${answer ?? "nothing"}` }], details: undefined };
    },
  });
};

/** A parent on the scripted model, with the fixtures Pi loads from their files. */
async function scripted(ui: FakeUi, paths: string[], extensions: ExtensionFactory[] = [pipkinSubagents]): Promise<AgentSession> {
  // The faux model only starts the session; every request goes to the scripted model.
  const { session } = await makeParent(dir, () => fauxAssistantMessage("wrong model"), {
    ui,
    extensions,
    extensionPaths: paths,
    tools: ["read", "bash", "edit", "write", "subagent", "ask_user"],
  });
  const model = session.modelRuntime.getModel("pipkin-test", "scripted");
  if (!model) throw new Error("the pipkin-test provider didn't register its model");
  await session.setModel(model);
  return session;
}

function reply(session: AgentSession): string {
  const last = session.messages.findLast((message) => message.role === "assistant");
  return last && last.role === "assistant" ? messageText(last) : "";
}

describe("pipkin-test-model", () => {
  it("answers in a real Pi session and drives an Explore subagent", async () => {
    await writeFile(path.join(dir.cwd, "README.md"), "Project readme\n");
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("Please run sa-one-explore");
    const [result] = subagentResults(session).values();
    expect(result?.details).toMatchObject({ agent: "Explore", status: "done" });
    expect(messageText(result)).toBe("Subagent 1 finished (ok: Project readme).");
    expect(reply(session)).toBe(`Finished sa-one-explore. Results:\n- subagent: ${messageText(result)}`);
    session.dispose();
  });

  it("lists its keywords for a message without one", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("hello");
    expect(reply(session)).toMatch(/^Pipkin test model\. Send one of these keywords: sa-one-explore, /);
    session.dispose();
  });

  it("runs the parent's bash beside a subagent's", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-parent-bash-while-sub");
    expect(reply(session)).toBe(
      "Finished sa-parent-bash-while-sub. Results:\n- bash: parent\n- subagent: Subagent 1 finished (ok: sub).",
    );
    session.dispose();
  });

  it("runs two General subagents with bash in parallel", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-two-general-bash");
    const results = [...subagentResults(session).values()].map((result) => messageText(result));
    expect(results).toEqual(["Subagent 1 finished (ok: x).", "Subagent 2 finished (ok: x)."]);
    expect(channel.sent.some((message) => message.runs.filter((run) => run.status === "running").length === 2)).toBe(true);
    session.dispose();
  });

  it("queues two of eight subagents", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-eight");
    const statuses = channel.sent.map((message) => message.runs.map((run) => run.status).join(","));
    expect(statuses).toContain([...Array(6).fill("running"), "queued", "queued"].join(","));
    expect(channel.latest().map((run) => run.status)).toEqual(Array(8).fill("done"));
    session.dispose();
  });

  it("sleeps in a subagent for sa-slow", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-slow");
    expect(messageText([...subagentResults(session).values()][0])).toBe("Subagent 1 finished (ok: slept).");
    session.dispose();
  });

  it("gives Explore no bash", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-explore-bash");
    const [result] = subagentResults(session).values();
    expect(messageText(result)).toMatch(/^Subagent 1 finished \(error: Tool bash not found/);
    session.dispose();
  });

  it("writes a reply past the result limit", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-long-output");
    const [result] = subagentResults(session).values();
    expect(messageText(result)).toMatch(/\[Cut at 50 KB of 7\d KB\. /);
    session.dispose();
  });

  it("fails the fifth of eight subagents with a provider error", async () => {
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-eight-fail");
    const results = [...subagentResults(session).values()];
    expect(results).toHaveLength(8);
    expect(results.filter((result) => result.isError).map((result) => messageText(result))).toEqual([
      "The subagent failed: Pipkin test model: scripted provider failure",
    ]);
    expect(channel.latest()[4]).toMatchObject({ status: "failed", error: "Pipkin test model: scripted provider failure" });
    session.dispose();
  });

  it("calls ask_user from a subagent", async () => {
    const ui = fakeUi(channel, (asked) => (asked.kind === "select" ? "Yes" : true));
    const session = await scripted(ui, [MODEL], [askUser, pipkinSubagents]);
    await session.prompt("sa-ask");
    expect(ui.asked).toEqual([expect.objectContaining({ title: "Pipkin test question: which do you pick?" })]);
    expect(ui.asked[0]?.runs).toEqual([expect.objectContaining({ inFlight: 1 })]);
    const [result] = subagentResults(session).values();
    expect(messageText(result)).toBe("Subagent 1 finished (ok: The user picked Yes).");
    session.dispose();
  });

  it("runs a named agent", async () => {
    await mkdir(path.join(dir.agentDir, "agents"), { recursive: true });
    await writeFile(path.join(dir.agentDir, "agents", "helper.md"), "---\nname: Helper\n---\nHelp.\n");
    const session = await scripted(fakeUi(channel), [MODEL]);
    await session.prompt("sa-agent:Helper");
    const [result] = subagentResults(session).values();
    expect(result?.details).toMatchObject({ agent: "Helper", status: "done" });
    session.dispose();
  });
});

describe("pipkin-test-gate", () => {
  it("asks about a subagent's write, and 'for this chat' covers the next one", async () => {
    const ui = fakeUi(channel, () => "Yes, for this chat");
    const session = await scripted(ui, [MODEL, GATE]);
    await session.prompt("sa-general-edit");
    expect(ui.asked).toEqual([
      expect.objectContaining({ title: "Allow write: pipkin-subagent-test.txt?", message: "Yes|Yes, for this chat|No" }),
    ]);
    expect(readFileSync(path.join(dir.cwd, "pipkin-subagent-test.txt"), "utf8")).toBe("Written by a Pipkin test subagent.\n");
    await session.prompt("sa-general-edit");
    expect(ui.asked).toHaveLength(1);
    session.dispose();
  });

  it("refuses on No, and the subagent carries on", async () => {
    const ui = fakeUi(channel, () => "No");
    const session = await scripted(ui, [MODEL, GATE]);
    await session.prompt("sa-general-edit");
    const [result] = subagentResults(session).values();
    expect(messageText(result)).toBe("Subagent 1 finished (error: The user refused this write call).");
    session.dispose();
  });

  it("asks about subagent calls when switched on, after its delay", async () => {
    process.env.PIPKIN_TEST_GATE_SUBAGENT = "1";
    await mkdir(path.join(dir.agentDir, "pipkin-test-gate"), { recursive: true });
    await writeFile(path.join(dir.agentDir, "pipkin-test-gate", "delay-ms"), "50");
    const ui = fakeUi(channel, () => "Yes");
    const session = await scripted(ui, [MODEL, GATE]);
    await session.prompt("sa-agent:General");
    expect(ui.asked).toEqual([expect.objectContaining({ title: "Allow subagent: run General?", runs: [] })]);
    expect([...subagentResults(session).values()][0]?.details).toMatchObject({ status: "done" });
    session.dispose();
  });
});
