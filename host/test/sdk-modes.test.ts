import { writeFile } from "node:fs/promises";
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

  const BUILTIN = ["anthropic", "openai-codex"].map((id) => ({
    id,
    name: expect.any(String),
    configured: false,
    type: null,
    models: expect.arrayContaining([expect.objectContaining({ id: expect.any(String) })]),
    extraIds: [],
    enabled: null,
  }));

  it("reports subscription providers and custom providers with their model settings", async () => {
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
