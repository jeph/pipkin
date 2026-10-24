import { readdirSync, readFileSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { type AssistantMessage, fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";
import { type ExtensionFactory, SessionManager } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { capped, RESULT_LIMIT_BYTES } from "../src/pi-extensions/pipkin-subagents/runs.js";
import { registry, report, reportSoon, type Run, THROTTLE_MS } from "../src/pi-extensions/pipkin-subagents/state.js";
import {
  type Dir,
  echoResults,
  eventually,
  type FakeChannel,
  type FakeUi,
  fakeUi,
  installChannel,
  makeDir,
  makeParent,
  messageText,
  type ParentOptions,
  pipkinSubagents,
  removeChannel,
  removeDir,
  type Script,
  subagentResults,
  type Turn,
} from "./subagents-harness.js";

let dir: Dir;
let channel: FakeChannel;

beforeEach(async () => {
  dir = await makeDir();
  channel = installChannel();
});

afterEach(async () => {
  removeChannel();
  await removeDir(dir);
});

interface Call {
  id: string;
  agent?: string;
  task: string;
  description?: string;
  model?: string;
}

/**
 * A parent that answers its first prompt by calling `subagent` once per [calls] in one reply, then echoes the
 * results; later prompts get a plain reply. Subagents' turns go to [child].
 */
function parentCalling(calls: Call[], child: Script): Script {
  return (turn) => {
    if (turn.who === "child") return child(turn);
    if (!turn.latest.startsWith("PARENT go")) return fauxAssistantMessage("parent idle");
    if (turn.step > 0) return echoResults(turn);
    return fauxAssistantMessage(
      calls.map(({ id, agent = "General", task, description = "a task", model }) =>
        fauxToolCall("subagent", { agent, task, description, ...(model ? { model } : {}) }, { id }),
      ),
      { stopReason: "toolUse" },
    );
  };
}

/** A subagent that runs [command] with bash, then reports what came back. */
function childRunning(command: string): Script {
  return (turn) => {
    if (turn.step === 0) return fauxAssistantMessage(fauxToolCall("bash", { command }), { stopReason: "toolUse" });
    return childReport(turn);
  };
}

function childReport(turn: Turn): AssistantMessage {
  const result = turn.lastToolResult;
  return fauxAssistantMessage(`child got ${result?.isError ? "error" : "ok"}: ${messageText(result).trim()}`);
}

interface GateCall {
  toolName: string;
  toolCallId: string;
  parentToolCallId: string | undefined;
}

/**
 * A permission extension: it records every tool call and, for [tools], asks the user (`ask`) or refuses without
 * asking (`block`).
 */
function gate(mode: "ask" | "block", tools: string[], log: GateCall[] = []): ExtensionFactory {
  return (pi) => {
    pi.on("tool_call", async (event, ctx) => {
      log.push({ toolName: event.toolName, toolCallId: event.toolCallId, parentToolCallId: event.parentToolCallId });
      if (!tools.includes(event.toolName)) return undefined;
      if (mode === "block") return { block: true, reason: `blocked ${event.toolName}` };
      const allowed = await ctx.ui.confirm("Allow?", event.toolName);
      return allowed ? undefined : { block: true, reason: `user denied ${event.toolName}` };
    });
  };
}

async function start(script: Script, options: Partial<ParentOptions> & { ui?: FakeUi } = {}) {
  const ui = options.ui ?? fakeUi(channel);
  const parent = await makeParent(dir, script, { ...options, ui });
  return { ...parent, ui };
}

function lastText(messages: readonly { role: string }[]): string {
  const last = messages.findLast((message) => message.role === "assistant");
  return last ? messageText(last as AssistantMessage) : "";
}

/** A promise and the function that settles it, for subagents that wait on the test. */
function latch(): { promise: Promise<void>; open: () => void } {
  const { promise, resolve } = Promise.withResolvers<void>();
  return { promise, open: () => resolve() };
}

/** A subagent that reads hello.txt, then reports what came back. */
const childReading: Script = (turn) => {
  if (turn.step === 0) return fauxAssistantMessage(fauxToolCall("read", { path: "hello.txt" }), { stopReason: "toolUse" });
  return childReport(turn);
};

const childDone: Script = () => fauxAssistantMessage("child done");

const childFailing: Script = () => {
  throw new Error("scripted provider failure");
};

/** A subagent replying with the names of its tools. */
const childListingTools: Script = (turn) => fauxAssistantMessage(turn.toolNames.toSorted().join(","));

/** A parent running bash itself, with no subagent. */
const parentRunningBash: Script = (turn) => {
  if (turn.step === 0) return fauxAssistantMessage(fauxToolCall("bash", { command: "echo own" }), { stopReason: "toolUse" });
  return fauxAssistantMessage("parent done");
};

/** Registers stand-ins for Pipkin todo and background, and a read-only extension tool. */
const otherTools: ExtensionFactory = (pi) => {
  for (const [name, readOnly] of [
    ["todo", false],
    ["background", false],
    ["lookup", true],
  ] as const) {
    pi.registerTool({
      name,
      label: name,
      description: `The ${name} tool.`,
      parameters: Type.Object({}),
      ...(readOnly ? { annotations: { readOnlyHint: true } } : {}),
      execute: async () => ({ content: [{ type: "text", text: name }], details: undefined }),
    });
  }
};

/** Another extension's `subagent` tool. */
const theirSubagent: ExtensionFactory = (pi) => {
  pi.registerTool({
    name: "subagent",
    label: "Their subagent",
    description: "Someone else's subagent tool.",
    parameters: Type.Object({}),
    execute: async () => ({ content: [{ type: "text", text: "theirs" }], details: undefined }),
  });
};

async function writeProjectAgent(): Promise<void> {
  await mkdir(path.join(dir.cwd, ".pi", "agents"), { recursive: true });
  await writeFile(path.join(dir.cwd, ".pi", "agents", "local.md"), "---\nname: Local\n---\nLOCAL-MARKER\n");
}

describe("stand-in tools", () => {
  it("run the subagent's calls through the parent's tool_call handlers", async () => {
    const log: GateCall[] = [];
    const { session } = await start(parentCalling([{ id: "sub1", agent: "Explore", task: "CHILD read" }], childReading), {
      extensions: [gate("ask", [], log), pipkinSubagents],
    });
    await session.prompt("PARENT go");
    expect(log).toContainEqual({ toolName: "read", toolCallId: "sub1/1", parentToolCallId: "sub1" });
    const result = subagentResults(session).get("sub1");
    expect(messageText(result)).toBe("child got ok: hello from the project");
    expect(result?.isError).toBe(false);
    session.dispose();
  });

  it("give the subagent a refused call as an error, and it carries on", async () => {
    const ui = fakeUi(channel, () => false);
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD run" }], childRunning("echo hi")), {
      extensions: [gate("ask", ["bash"]), pipkinSubagents],
      ui,
    });
    await session.prompt("PARENT go");
    expect(ui.asked).toHaveLength(1);
    const result = subagentResults(session).get("sub1");
    expect(messageText(result)).toBe("child got error: user denied bash");
    expect(result?.details).toMatchObject({ status: "done" });
    session.dispose();
  });
});

describe("permission questions", () => {
  for (const order of ["before", "after"] as const) {
    const extensions = (factory: ExtensionFactory): ExtensionFactory[] =>
      order === "before" ? [factory, pipkinSubagents] : [pipkinSubagents, factory];

    it(`come from a subagent with exactly its run in flight, with the gate loaded ${order} Pipkin subagents`, async () => {
      const ui = fakeUi(channel, () => true);
      const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD run" }], childRunning("echo hi")), {
        extensions: extensions(gate("ask", ["bash"])),
        ui,
      });
      await session.prompt("PARENT go");
      expect(ui.asked).toHaveLength(1);
      expect(ui.asked[0]?.runs).toEqual([expect.objectContaining({ toolCallId: "sub1", status: "running", inFlight: 1 })]);
      expect(channel.latest()).toEqual([expect.objectContaining({ status: "done", inFlight: 0 })]);
      expect(messageText(subagentResults(session).get("sub1"))).toBe("child got ok: hi");
      session.dispose();
    });

    it(`come from the parent with no run in flight, with the gate loaded ${order} Pipkin subagents`, async () => {
      const ui = fakeUi(channel, () => true);
      const { session } = await start(parentRunningBash, { extensions: extensions(gate("ask", ["bash"])), ui });
      await session.prompt("PARENT go");
      expect(ui.asked).toHaveLength(1);
      expect(ui.asked[0]?.runs.filter((run) => run.inFlight > 0)).toEqual([]);
      session.dispose();
    });

    it(`about a subagent call come before its run exists, with the gate loaded ${order} Pipkin subagents`, async () => {
      const ui = fakeUi(channel, () => true);
      const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD work" }], childDone), {
        extensions: extensions(gate("ask", ["subagent"])),
        ui,
      });
      await session.prompt("PARENT go");
      expect(ui.asked).toHaveLength(1);
      expect(ui.asked[0]?.message).toBe("subagent");
      expect(ui.asked[0]?.runs).toEqual([]);
      expect(messageText(subagentResults(session).get("sub1"))).toBe("child done");
      session.dispose();
    });
  }

  it("refused for a subagent call start no run", async () => {
    const ui = fakeUi(channel, () => false);
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD work" }], () => fauxAssistantMessage("x")), {
      extensions: [gate("ask", ["subagent"]), pipkinSubagents],
      ui,
    });
    await session.prompt("PARENT go");
    const result = subagentResults(session).get("sub1");
    expect(result?.isError).toBe(true);
    expect(messageText(result)).toContain("user denied subagent");
    expect(channel.sent.flatMap((message) => message.runs)).toEqual([]);
    session.dispose();
  });

  it("never asked, when a gate refuses outright, still count the call in flight until it comes back", async () => {
    const ui = fakeUi(channel);
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD run" }], childRunning("echo hi")), {
      extensions: [gate("block", ["bash"]), pipkinSubagents],
      ui,
    });
    await session.prompt("PARENT go");
    expect(ui.asked).toEqual([]);
    const inFlight = channel.sent.map((message) => message.runs[0]?.inFlight).filter((value) => value !== undefined);
    expect(inFlight).toContain(1);
    expect(inFlight.at(-1)).toBe(0);
    expect(messageText(subagentResults(session).get("sub1"))).toBe("child got error: blocked bash");
    session.dispose();
  });
});

describe("parallel runs", () => {
  it("run two calls from one reply at the same time", async () => {
    const calls = [
      { id: "subA", task: "CHILD A" },
      { id: "subB", task: "CHILD B" },
    ];
    const { session } = await start(parentCalling(calls, childRunning("sleep 0.3; echo x")));
    await session.prompt("PARENT go");
    const together = channel.sent.some(
      (message) => message.runs.length === 2 && message.runs.every((run) => run.status === "running" && run.inFlight === 1),
    );
    expect(together).toBe(true);
    const results = subagentResults(session);
    expect(messageText(results.get("subA"))).toBe("child got ok: x");
    expect(messageText(results.get("subB"))).toBe("child got ok: x");
    session.dispose();
  });

  it("run four of six calls at once and queue the other two", async () => {
    const release = latch();
    const calls = Array.from({ length: 6 }, (_, index) => ({ id: `sub${index + 1}`, task: `CHILD ${index + 1}` }));
    const child: Script = async (turn) => {
      await release.promise;
      return fauxAssistantMessage(`done ${turn.first}`);
    };
    const { session } = await start(parentCalling(calls, child));
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest().length === 6);
    await eventually(() => channel.latest().filter((run) => run.status === "running").length === 4);
    const queued = channel.latest().filter((run) => run.status === "queued");
    expect(queued.map((run) => run.toolCallId)).toEqual(["sub5", "sub6"]);
    expect(queued.every((run) => run.startedAt === null)).toBe(true);
    release.open();
    await prompt;
    expect(channel.latest().map((run) => run.status)).toEqual(Array(6).fill("done"));
    const results = subagentResults(session);
    for (const call of calls) expect(messageText(results.get(call.id))).toBe(`done ${call.task}`);
    session.dispose();
  });
});

describe("stop", () => {
  it("ends the run the user stops, and the parent carries on", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD sleep" }], childRunning("sleep 30")));
    const began = Date.now();
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest()[0]?.inFlight === 1);
    channel.fromApp({ action: "stop", id: channel.latest()[0]?.id });
    await prompt;
    expect(Date.now() - began).toBeLessThan(10_000);
    const result = subagentResults(session).get("sub1");
    expect(result?.isError).toBe(false);
    expect(messageText(result)).toBe("The user stopped the subagent before it finished.");
    expect(result?.details).toMatchObject({ status: "stopped" });
    expect(channel.latest()).toEqual([expect.objectContaining({ status: "stopped", inFlight: 0 })]);
    expect(lastText(session.messages)).toMatch(/^parent done/);
    session.dispose();
  });

  it("of a queued run means it never starts", async () => {
    const release = latch();
    const calls = Array.from({ length: 5 }, (_, index) => ({ id: `sub${index + 1}`, task: `CHILD ${index + 1}` }));
    const child: Script = async () => {
      await release.promise;
      return fauxAssistantMessage("done");
    };
    const { session } = await start(parentCalling(calls, child));
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest().some((run) => run.status === "queued"));
    const queued = channel.latest().find((run) => run.status === "queued");
    channel.fromApp({ action: "stop", id: queued?.id });
    await eventually(() => channel.latest().some((run) => run.status === "stopped"));
    release.open();
    await prompt;
    const result = subagentResults(session).get("sub5");
    expect(messageText(result)).toBe("The user stopped the subagent before it started.");
    expect(channel.latest().find((run) => run.toolCallId === "sub5")).toMatchObject({ status: "stopped", startedAt: null });
    session.dispose();
  });

  it("all ends every run", async () => {
    const calls = [
      { id: "subA", task: "CHILD A" },
      { id: "subB", task: "CHILD B" },
    ];
    const { session } = await start(parentCalling(calls, childRunning("sleep 30")));
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest().filter((run) => run.inFlight === 1).length === 2);
    channel.fromApp({ action: "stopAll" });
    await prompt;
    expect(channel.latest().map((run) => run.status)).toEqual(["stopped", "stopped"]);
    session.dispose();
  });

  it("of the parent's run ends its subagents", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD sleep" }], childRunning("sleep 30")));
    const began = Date.now();
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest()[0]?.inFlight === 1);
    await session.abort();
    await prompt;
    expect(Date.now() - began).toBeLessThan(10_000);
    expect(channel.latest()).toEqual([expect.objectContaining({ status: "stopped", inFlight: 0 })]);
    session.dispose();
  });

  // Where a Stop lands relative to the child's run start is the point, so these use the real event loop and clock:
  // fake timers would move the abort, not reproduce it.
  const timings: Record<string, (abort: () => void) => void> = {
    synchronously: (abort) => abort(),
    "in a microtask": (abort) => queueMicrotask(abort),
    "on the next turn of the event loop": (abort) => setImmediate(abort),
    "after a millisecond": (abort) => setTimeout(abort, 1),
  };
  for (const [when, schedule] of Object.entries(timings)) {
    it(`of the parent's run is not lost when it lands ${when} as the subagent's prompt starts`, async () => {
      let child = 0;
      const script: Script = (turn) => {
        child++;
        return childRunning("sleep 30")(turn);
      };
      const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD sleep" }], script), { persist: true });
      let fired = false;
      channel.onSend = (message) => {
        if (fired || !message.runs[0]?.sessionFile) return;
        fired = true;
        schedule(() => void session.abort());
      };
      const began = Date.now();
      await session.prompt("PARENT go");
      expect(fired).toBe(true);
      expect(Date.now() - began).toBeLessThan(10_000);
      expect(channel.latest()).toEqual([expect.objectContaining({ status: "stopped" })]);
      // The subagent's bash never got to run its sleep through.
      expect(child).toBeLessThanOrEqual(1);
      session.dispose();
    });
  }
});

describe("steer", () => {
  it("reaches the running subagent after its current tool", async () => {
    const release = latch();
    const child: Script = async (turn) => {
      if (turn.latest.startsWith("STEER")) return fauxAssistantMessage(`steered: ${turn.latest}`);
      if (turn.step > 0) return fauxAssistantMessage("never steered");
      await release.promise;
      return fauxAssistantMessage(fauxToolCall("bash", { command: "echo working" }), { stopReason: "toolUse" });
    };
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD work" }], child));
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest()[0]?.turn === 1);
    channel.fromApp({ action: "steer", id: channel.latest()[0]?.id, text: "STEER look at auth" });
    release.open();
    await prompt;
    expect(messageText(subagentResults(session).get("sub1"))).toBe("steered: STEER look at auth");
    session.dispose();
  });

  it("lists the steers the subagent hasn't read, until it reads each", async () => {
    const release = latch();
    const child: Script = async (turn) => {
      if (turn.latest.startsWith("STEER")) return fauxAssistantMessage(`steered: ${turn.latest}`);
      if (turn.step > 0) return fauxAssistantMessage("never steered");
      await release.promise;
      return fauxAssistantMessage(fauxToolCall("bash", { command: "echo working" }), { stopReason: "toolUse" });
    };
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD work" }], child));
    const prompt = session.prompt("PARENT go");
    await eventually(() => channel.latest()[0]?.turn === 1);
    expect(channel.latest()[0]?.steering).toEqual([]);
    const id = channel.latest()[0]?.id;
    channel.fromApp({ action: "steer", id, text: "STEER one" });
    channel.fromApp({ action: "steer", id, text: "STEER two" });
    await eventually(() => channel.latest()[0]?.steering.length === 2);
    expect(channel.latest()[0]).toMatchObject({ status: "running", steering: ["STEER one", "STEER two"] });
    release.open();
    await prompt;
    // Pi hands them to the child after its bash call, and the list empties while the child still runs.
    const readAt = channel.sent.findLastIndex((message) => message.runs[0]?.steering.length === 2);
    const read = channel.sent.slice(readAt + 1).find((message) => message.runs[0]?.steering.length === 0);
    expect(read?.runs[0]?.status).toBe("running");
    expect(channel.latest()).toEqual([expect.objectContaining({ status: "done", steering: [] })]);
    session.dispose();
  });
});

describe("transcripts", () => {
  it("are saved beside the parent's under subagents/<parent id>/, linked both ways", async () => {
    const { session } = await start(
      parentCalling([{ id: "sub1", agent: "Explore", task: "CHILD read", description: "read hello" }], childReading),
      { persist: true },
    );
    await session.prompt("PARENT go");
    const parentFile = session.sessionManager.getSessionFile() ?? "";
    const folder = path.join(path.dirname(parentFile), "subagents", session.sessionManager.getSessionId());
    const files = readdirSync(folder);
    expect(files).toHaveLength(1);
    const file = path.join(folder, files[0] ?? "");
    const details = subagentResults(session).get("sub1")?.details;
    expect(details).toMatchObject({ sessionFile: file, agent: "Explore", description: "read hello", status: "done" });
    const lines = readFileSync(file, "utf8")
      .trim()
      .split("\n")
      .map((line) => JSON.parse(line) as Record<string, unknown>);
    expect(lines[0]).toMatchObject({ type: "session", parentSession: parentFile });
    expect(lines).toContainEqual(
      expect.objectContaining({
        type: "custom",
        customType: "pipkin-subagent",
        data: { agent: "Explore", description: "read hello", task: "CHILD read", toolCallId: "sub1" },
      }),
    );
    const messages = lines.filter((line) => line.type === "message").length;
    // What the host's session_messages mode gives the app for this transcript.
    const context = SessionManager.open(file).buildSessionContext().messages.map((message) => message.role);
    expect(context).toEqual(["system", "user", "assistant", "toolResult", "assistant"]);
    expect(channel.latest()).toEqual([expect.objectContaining({ sessionFile: file, messages })]);
    session.dispose();
  });

  it("stay in memory when the parent's session does", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD hi" }], () => fauxAssistantMessage("hi")));
    await session.prompt("PARENT go");
    expect(subagentResults(session).get("sub1")?.details).toMatchObject({ sessionFile: null, status: "done" });
    session.dispose();
  });
});

describe("results", () => {
  it("cut a long reply at 50 KB and say where the transcript is", async () => {
    const long = "x".repeat(60 * 1024);
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD long" }], () => fauxAssistantMessage(long)), {
      persist: true,
    });
    await session.prompt("PARENT go");
    const result = subagentResults(session).get("sub1");
    const details = result?.details as { sessionFile: string; usage: { totalTokens: number; input: number } };
    const text = messageText(result);
    expect(text.startsWith("x".repeat(RESULT_LIMIT_BYTES))).toBe(true);
    expect(text).not.toContain("x".repeat(RESULT_LIMIT_BYTES + 1));
    expect(text).toContain(`Cut at 50 KB of 60 KB. The full reply is in the subagent's transcript: ${details.sessionFile}`);
    expect(details.usage.totalTokens).toBeGreaterThan(0);
    expect(details.usage.input).toBeGreaterThan(0);
    expect(result).toMatchObject({ usage: { totalTokens: details.usage.totalTokens } });
    session.dispose();
  });

  it("cut on a character boundary", () => {
    const text = capped("é".repeat(RESULT_LIMIT_BYTES), null);
    expect(text).toContain("é".repeat(RESULT_LIMIT_BYTES / 2));
    expect(text).not.toContain("\uFFFD");
    expect(text).toContain("The subagent's transcript wasn't saved");
  });

  it("fail the call with the subagent's model error", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD fail" }], childFailing));
    await session.prompt("PARENT go");
    const result = subagentResults(session).get("sub1");
    expect(result?.isError).toBe(true);
    expect(messageText(result)).toBe("The subagent failed: scripted provider failure");
    expect(result?.details).toMatchObject({ status: "failed" });
    expect(channel.latest()).toEqual([expect.objectContaining({ status: "failed", error: "scripted provider failure" })]);
    session.dispose();
  });
});

describe("runs messages", () => {
  it("follow a run and drop it at the user's next message", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", agent: "Explore", task: "CHILD read" }], childReading));
    await session.prompt("PARENT go");
    expect(channel.sent.every((message) => message.version === 1)).toBe(true);
    const runs = channel.sent.flatMap((message) => message.runs);
    expect([...new Set(runs.map((run) => run.status))]).toEqual(["running", "done"]);
    expect(runs.map((run) => run.inFlight)).toContain(1);
    expect(runs.map((run) => run.lastActivity)).toContain("read hello.txt");
    expect(channel.latest()).toEqual([
      {
        id: "r1",
        toolCallId: "sub1",
        agent: "Explore",
        description: "a task",
        status: "done",
        startedAt: expect.any(Number),
        endedAt: expect.any(Number),
        turn: 2,
        tokens: expect.any(Number),
        lastActivity: null,
        error: null,
        sessionFile: null,
        // The system prompt, the task, the read call, its result and the reply.
        messages: 5,
        inFlight: 0,
        steering: [],
      },
    ]);
    await session.prompt("PARENT again");
    expect(channel.latest()).toEqual([]);
    session.dispose();
  });

  it("go again after a reload", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD hi" }], () => fauxAssistantMessage("hi")));
    await session.prompt("PARENT go");
    const before = channel.sent.length;
    await session.reload();
    expect(channel.sent.length).toBe(before + 1);
    expect(channel.latest()).toEqual([expect.objectContaining({ toolCallId: "sub1", status: "done" })]);
    session.dispose();
  });

  it("send turn, token and activity changes at most every 250 ms", () => {
    vi.useFakeTimers();
    try {
      const state = registry();
      state.current = "s1";
      const run = { id: "r1", toolCallId: "c1", sessionId: "s1", status: "running", turn: 0, inFlight: 0 } as Run;
      state.runs.set(run.id, run);
      report(state);
      const sent = channel.sent.length;
      for (let turn = 1; turn <= 10; turn++) {
        run.turn = turn;
        reportSoon(state);
        vi.advanceTimersByTime(20);
      }
      // 200 ms in: nothing yet, then the latest at 250 ms.
      expect(channel.sent.length).toBe(sent);
      vi.advanceTimersByTime(THROTTLE_MS);
      expect(channel.sent.length).toBe(sent + 1);
      expect(channel.latest()[0]?.turn).toBe(10);
      // A change the app needs at once goes straight out.
      run.inFlight = 1;
      report(state);
      expect(channel.sent.length).toBe(sent + 2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("agents", () => {
  it("give Explore read-only tools and General all but the parent's own", async () => {
    const calls = [
      { id: "subE", agent: "Explore", task: "CHILD E" },
      { id: "subG", agent: "General", task: "CHILD G" },
    ];
    const { session } = await start(parentCalling(calls, childListingTools), {
      extensions: [otherTools, pipkinSubagents],
      tools: ["read", "bash", "edit", "write", "grep", "find", "ls", "subagent", "todo", "background", "lookup"],
    });
    await session.prompt("PARENT go");
    const results = subagentResults(session);
    expect(messageText(results.get("subE"))).toBe("find,grep,lookup,ls,read");
    expect(messageText(results.get("subG"))).toBe("bash,edit,find,grep,lookup,ls,read,write");
    session.dispose();
  });

  it("fail an unknown agent's call, listing the agents", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", agent: "Nope", task: "CHILD x" }], () => fauxAssistantMessage("x")));
    await session.prompt("PARENT go");
    const result = subagentResults(session).get("sub1");
    expect(result?.isError).toBe(true);
    expect(messageText(result)).toBe('Unknown agent "Nope". Available agents: Explore, General.');
    expect(channel.sent.flatMap((message) => message.runs)).toEqual([]);
    session.dispose();
  });

  it("fail a malformed definition's call, naming its file", async () => {
    const file = path.join(dir.agentDir, "agents", "broken.md");
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, "---\nname: [unclosed\n---\nBody\n");
    const { session } = await start(
      parentCalling(
        [
          { id: "sub1", agent: "broken", task: "CHILD x" },
          { id: "sub2", agent: "Nope", task: "CHILD y" },
        ],
        () => fauxAssistantMessage("x"),
      ),
    );
    await session.prompt("PARENT go");
    const results = subagentResults(session);
    expect(results.get("sub1")?.isError).toBe(true);
    expect(messageText(results.get("sub1"))).toContain(`The agent definition ${file} could not be read:`);
    expect(messageText(results.get("sub2"))).toContain(`These definition files could not be read: ${file}`);
    session.dispose();
  });

  it("use a user agent's tools and prompt, with the project's AGENTS.md and the user's skills", async () => {
    await mkdir(path.join(dir.agentDir, "agents"), { recursive: true });
    await writeFile(
      path.join(dir.agentDir, "agents", "reviewer.md"),
      "---\nname: Reviewer\ndescription: Reviews code\ntools: read, bash\n---\nREVIEWER-MARKER: be thorough.\n",
    );
    await writeFile(path.join(dir.cwd, "AGENTS.md"), "PROJECT-MARKER: say banana.\n");
    await mkdir(path.join(dir.agentDir, "skills", "demo-skill"), { recursive: true });
    await writeFile(
      path.join(dir.agentDir, "skills", "demo-skill", "SKILL.md"),
      "---\nname: demo-skill\ndescription: SKILL-MARKER demo skill\n---\nDo the demo.\n",
    );
    let seen: Turn | undefined;
    const child: Script = (turn) => {
      seen = turn;
      return fauxAssistantMessage("reviewed");
    };
    const { session } = await start(parentCalling([{ id: "sub1", agent: "Reviewer", task: "CHILD review" }], child));
    await session.prompt("PARENT go");
    expect(messageText(subagentResults(session).get("sub1"))).toBe("reviewed");
    expect(seen?.toolNames.toSorted()).toEqual(["bash", "read"]);
    expect(seen?.systemPrompt).toContain("REVIEWER-MARKER");
    expect(seen?.systemPrompt).toContain("PROJECT-MARKER");
    expect(seen?.systemPrompt).toContain("SKILL-MARKER");
    session.dispose();
  });

  it("ignore a project's agents when the project is not trusted", async () => {
    await writeProjectAgent();
    const { session } = await start(parentCalling([{ id: "sub1", agent: "Local", task: "CHILD local" }], childDone), {
      projectTrusted: false,
    });
    await session.prompt("PARENT go");
    const result = subagentResults(session).get("sub1");
    expect(result?.isError).toBe(true);
    expect(messageText(result)).toContain('Unknown agent "Local"');
    session.dispose();
  });

  it("load a project's agents when the project is trusted", async () => {
    await writeProjectAgent();
    let prompt = "";
    const child: Script = (turn) => {
      prompt = turn.systemPrompt;
      return fauxAssistantMessage("local done");
    };
    const { session } = await start(parentCalling([{ id: "sub1", agent: "Local", task: "CHILD local" }], child), {
      projectTrusted: true,
    });
    await session.prompt("PARENT go");
    expect(messageText(subagentResults(session).get("sub1"))).toBe("local done");
    expect(prompt).toContain("LOCAL-MARKER");
    session.dispose();
  });

  it("use the model the call names, and fail one that doesn't exist", async () => {
    const calls = [
      { id: "sub1", task: "CHILD model", model: "faux/faux-2" },
      { id: "sub2", task: "CHILD missing", model: "faux/nope" },
    ];
    const { session } = await start(parentCalling(calls, (turn) => fauxAssistantMessage(`model ${turn.model}`)));
    await session.prompt("PARENT go");
    const results = subagentResults(session);
    expect(messageText(results.get("sub1"))).toBe("model faux-2");
    expect(results.get("sub2")?.isError).toBe(true);
    expect(messageText(results.get("sub2"))).toContain('Unknown model "faux/nope"');
    session.dispose();
  });

  it("stand aside when another extension has a subagent tool, and tell the app so after each session start", async () => {
    const { session } = await start(parentCalling([{ id: "sub1", task: "CHILD x" }], () => fauxAssistantMessage("x")), {
      extensions: [theirSubagent, pipkinSubagents],
    });
    const standingAside = { version: 1, standingAside: true, runs: [] };
    expect(channel.sent).toEqual([standingAside]);
    await session.prompt("PARENT go");
    expect(messageText(subagentResults(session).get("sub1"))).toBe("theirs");
    expect(channel.sent).toEqual([standingAside]);
    await session.reload();
    expect(channel.sent).toEqual([standingAside, standingAside]);
    session.dispose();
  });

  it("say they don't stand aside when the subagent tool is theirs", async () => {
    const { session } = await start(parentCalling([], childDone));
    expect(channel.sent).toEqual([{ version: 1, standingAside: false, runs: [] }]);
    session.dispose();
  });
});
