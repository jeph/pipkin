import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

describe("modes backed by the Pi SDK", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("lists no sessions in a fresh config", async () => {
    expect(await runMode("sessions", dirs, {})).toEqual({ type: "pipkin_result", ok: true, data: { sessions: [] } });
  });

  /** Writes a Pi session file for [cwd] holding [messages], one entry each in a single branch. */
  async function writeSession(cwd: string, id: string, messages: object[]): Promise<string> {
    const dir = path.join(dirs.configDir, "sessions", `--${cwd.replace(/^\//, "").replaceAll("/", "-")}--`);
    await mkdir(dir, { recursive: true });
    const file = path.join(dir, `2026-10-12T00-00-00-000Z_${id}.jsonl`);
    const header = { type: "session", version: 3, id, timestamp: "2026-10-12T00:00:00.000Z", cwd };
    const entries = messages.map((message, index) => ({
      type: "message",
      id: `e${index}`,
      parentId: index === 0 ? null : `e${index - 1}`,
      timestamp: "2026-10-12T00:00:01.000Z",
      message,
    }));
    await writeFile(file, [header, ...entries].map((line) => JSON.stringify(line) + "\n").join(""));
    return file;
  }

  const USER = { role: "user", content: "hello", timestamp: 1 };
  const ASSISTANT = {
    role: "assistant",
    content: [{ type: "text", text: "hi there" }],
    api: "openai-responses",
    provider: "p",
    model: "m",
    usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
    stopReason: "stop",
    timestamp: 2,
  };

  it("tells whether each session's workspace folder still exists", async () => {
    const gone = path.join(dirs.base, "gone");
    await writeSession(dirs.home, "kept", [USER, ASSISTANT]);
    await writeSession(gone, "lost", [USER, ASSISTANT]);
    expect(await runMode("sessions", dirs, {})).toMatchObject({
      ok: true,
      data: {
        sessions: expect.arrayContaining([
          expect.objectContaining({ id: "kept", cwd: dirs.home, cwdExists: true }),
          expect.objectContaining({ id: "lost", cwd: gone, cwdExists: false }),
        ]),
      },
    });
  });

  it("reads a session's messages without changing its file", async () => {
    const file = await writeSession(dirs.home, "s1", [USER, ASSISTANT]);
    const before = await readFile(file, "utf8");
    expect(await runMode("session_messages", dirs, { path: file })).toEqual({
      type: "pipkin_result",
      ok: true,
      data: { messages: [USER, ASSISTANT] },
    });
    expect(await readFile(file, "utf8")).toBe(before);
  });

  it("refuses to read a transcript outside the sessions folder", async () => {
    await mkdir(path.join(dirs.configDir, "sessions"), { recursive: true });
    const outside = path.join(dirs.configDir, "settings.json");
    await writeFile(outside, "{}");
    expect(await runMode("session_messages", dirs, { path: outside })).toMatchObject({ ok: false, code: "outside_sessions" });
  });

  it("reports a transcript whose file is gone as not found", async () => {
    await mkdir(path.join(dirs.configDir, "sessions"), { recursive: true });
    const gone = path.join(dirs.configDir, "sessions", "gone.jsonl");
    expect(await runMode("session_messages", dirs, { path: gone })).toMatchObject({ ok: false, code: "not_found" });
  });

  /** Writes a subagent transcript of [parent] the way Pipkin subagents saves one, started at [time]. */
  async function writeSubagent(parent: string, parentId: string, id: string, time: string, messages: object[]) {
    const dir = path.join(path.dirname(parent), "subagents", parentId);
    await mkdir(dir, { recursive: true });
    const file = path.join(dir, `${time.replaceAll(/[:.]/g, "-")}_${id}.jsonl`);
    const header = { type: "session", version: 3, id, timestamp: time, cwd: dirs.home, parentSession: parent };
    const link = {
      type: "custom",
      customType: "pipkin-subagent",
      id: "c0",
      parentId: null,
      timestamp: time,
      data: { agent: "Explore", description: `task ${id}`, task: `Do ${id}`, toolCallId: `call_${id}` },
    };
    const entries = messages.map((message, index) => ({
      type: "message",
      id: `e${index}`,
      parentId: index === 0 ? "c0" : `e${index - 1}`,
      timestamp: time,
      message,
    }));
    await writeFile(file, [header, link, ...entries].map((line) => JSON.stringify(line) + "\n").join(""));
    return file;
  }

  it("counts each session's subagents and keeps their transcripts out of the list", async () => {
    const parent = await writeSession(dirs.home, "parent", [USER, ASSISTANT]);
    await writeSession(dirs.home, "lonely", [USER, ASSISTANT]);
    await writeSubagent(parent, "parent", "s1", "2026-10-12T00:00:02.000Z", [USER, ASSISTANT]);
    await writeSubagent(parent, "parent", "s2", "2026-10-12T00:00:03.000Z", [USER]);
    await writeFile(path.join(path.dirname(parent), "subagents", "parent", "notes.txt"), "not a transcript");
    const result = await runMode("sessions", dirs, {});
    expect(result).toMatchObject({ ok: true });
    const listed = (result.data as { sessions: { id: string; subagents: number }[] }).sessions;
    expect(listed.map(({ id, subagents }) => ({ id, subagents })).toSorted((a, b) => a.id.localeCompare(b.id))).toEqual([
      { id: "lonely", subagents: 0 },
      { id: "parent", subagents: 2 },
    ]);
  });

  it("lists a session's subagent transcripts, oldest first", async () => {
    const parent = await writeSession(dirs.home, "parent", [USER, ASSISTANT]);
    const later = await writeSubagent(parent, "parent", "s2", "2026-10-12T00:00:03.000Z", [USER]);
    const earlier = await writeSubagent(parent, "parent", "s1", "2026-10-12T00:00:02.000Z", [USER, ASSISTANT]);
    expect(await runMode("subagent_sessions", dirs, { path: parent })).toEqual({
      type: "pipkin_result",
      ok: true,
      data: {
        sessions: [
          {
            path: earlier,
            toolCallId: "call_s1",
            agent: "Explore",
            description: "task s1",
            created: Date.parse("2026-10-12T00:00:02.000Z"),
            modified: expect.any(Number),
            messageCount: 2,
          },
          {
            path: later,
            toolCallId: "call_s2",
            agent: "Explore",
            description: "task s2",
            created: Date.parse("2026-10-12T00:00:03.000Z"),
            modified: expect.any(Number),
            messageCount: 1,
          },
        ],
      },
    });
  });

  it("lists no subagents for a session that has none", async () => {
    const parent = await writeSession(dirs.home, "parent", [USER, ASSISTANT]);
    expect(await runMode("subagent_sessions", dirs, { path: parent })).toEqual({
      type: "pipkin_result",
      ok: true,
      data: { sessions: [] },
    });
  });

  it("refuses to list subagents of a file outside the sessions folder", async () => {
    await mkdir(path.join(dirs.configDir, "sessions"), { recursive: true });
    const outside = path.join(dirs.configDir, "settings.json");
    await writeFile(outside, "{}");
    expect(await runMode("subagent_sessions", dirs, { path: outside })).toMatchObject({ ok: false, code: "outside_sessions" });
  });

  it("reads a subagent's messages from its transcript", async () => {
    const parent = await writeSession(dirs.home, "parent", [USER, ASSISTANT]);
    const file = await writeSubagent(parent, "parent", "s1", "2026-10-12T00:00:02.000Z", [USER, ASSISTANT]);
    expect(await runMode("session_messages", dirs, { path: file })).toEqual({
      type: "pipkin_result",
      ok: true,
      data: { messages: [USER, ASSISTANT] },
    });
  });

  const BUILTIN = ["anthropic", "openai-codex", "google", "zai", "kimi-coding", "openrouter"].map((id) => ({
    id,
    name: expect.any(String),
    configured: false,
    type: null,
    models: expect.arrayContaining([expect.objectContaining({ id: expect.any(String) })]),
    extraIds: [],
    enabled: null,
  }));

  it("reports Pipkin's built-in providers and custom providers with their model settings", async () => {
    const provider = {
      baseUrl: "https://example.test/v1",
      api: "openai-responses",
      apiKey: "k",
      models: [
        { id: "m1", name: "M One", input: ["text", "image"], headers: { "X-M": "1" }, cost: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0, tiers: [] } },
        { id: "m2", reasoning: true, thinkingLevelMap: { off: null, high: "high" }, contextWindow: 1000, maxTokens: 100 },
      ],
    };
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers: { "codex-lb": provider } }));
    expect(await runMode("auth_status", dirs, {})).toEqual({
      type: "pipkin_result",
      ok: true,
      data: {
        builtin: BUILTIN,
        custom: [
          {
            id: "codex-lb",
            baseUrl: provider.baseUrl,
            api: provider.api,
            apiKey: "k",
            models: [
              { id: "m1", name: "M One", input: ["text", "image"], cost: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0 } },
              { id: "m2", reasoning: true, thinkingLevelMap: { off: null, high: "high" }, contextWindow: 1000, maxTokens: 100 },
            ],
          },
        ],
        scope: null,
        npmPackages: [],
      },
    });
  });

  it("skips wrongly typed model settings and turns bare model ids into models", async () => {
    const provider = {
      baseUrl: "https://example.test/v1",
      api: "openai-completions",
      apiKey: "k",
      models: [
        { id: "m1", name: 7, reasoning: "yes", input: ["text", "image"], contextWindow: "big", cost: { input: 1 } },
        "m2",
        { name: "no id" },
      ],
    };
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers: { local: provider } }));
    expect(await runMode("auth_status", dirs, {})).toEqual({
      type: "pipkin_result",
      ok: true,
      data: {
        builtin: BUILTIN,
        custom: [
          {
            id: "local",
            baseUrl: provider.baseUrl,
            api: provider.api,
            apiKey: "k",
            models: [{ id: "m1", input: ["text", "image"] }, { id: "m2" }],
          },
        ],
        scope: null,
        npmPackages: [],
        // Pi itself rejects this models.json; the error is passed through for the app to show.
        modelsError: expect.any(String),
      },
    });
  });

  it("reports an unparseable models.json as modelsError with no custom providers", async () => {
    await writeFile(path.join(dirs.configDir, "models.json"), "{");
    const result = await runMode("auth_status", dirs, {});
    expect(result.data).toMatchObject({ custom: [], modelsError: expect.any(String) });
  });
});
