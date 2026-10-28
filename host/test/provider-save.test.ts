import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

const SAVE = {
  id: "codex-lb",
  baseUrl: "https://example.test/v1",
  api: "openai-responses",
  apiKey: "sk-test",
  models: [
    { id: "gpt-a", reasoning: true, input: ["text", "image"], contextWindow: 272_000 },
    { id: "gpt-b", name: "GPT B", thinkingLevelMap: { off: null, low: "low" }, cost: { input: 1, output: 2, cacheRead: 0.1, cacheWrite: 0 } },
  ],
};

describe("provider_save mode", () => {
  let dirs: Dirs;
  let modelsPath: string;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    modelsPath = path.join(dirs.configDir, "models.json");
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  async function saved(): Promise<unknown> {
    return JSON.parse(await readFile(modelsPath, "utf8"));
  }

  it("adds the provider and keeps other providers and keys", async () => {
    const other = { baseUrl: "http://localhost:11434/v1", api: "openai-completions", apiKey: "x", models: [{ id: "llama" }] };
    await writeFile(modelsPath, JSON.stringify({ extra: true, providers: { local: other } }));
    expect(await runMode("provider_save", dirs, SAVE)).toEqual({ type: "pipkin_result", ok: true, data: {} });
    expect(await saved()).toEqual({
      extra: true,
      providers: {
        local: other,
        "codex-lb": { baseUrl: SAVE.baseUrl, api: SAVE.api, apiKey: "sk-test", models: SAVE.models },
      },
    });
  });

  it("keeps settings Pipkin does not manage and drops cleared fields and models", async () => {
    const tiers = [{ inputTokensAbove: 272_000, input: 10, output: 45, cacheRead: 1, cacheWrite: 0 }];
    const existing = {
      baseUrl: "https://old.test/v1",
      api: "openai-completions",
      apiKey: "sk-old",
      headers: { "X-Provider": "1" },
      compat: { supportsStrictMode: false },
      models: [
        {
          id: "gpt-a",
          name: "Old name",
          reasoning: true,
          contextWindow: 1000,
          headers: { "X-Model": "y" },
          compat: { supportsStrictMode: true },
          samplingParams: { temperature: 0.2 },
          cost: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0, tiers },
        },
        { id: "gone", samplingParams: { top_p: 0.9 } },
      ],
    };
    await writeFile(modelsPath, JSON.stringify({ providers: { "codex-lb": existing } }));
    const models = [
      { id: "gpt-a", reasoning: false, cost: { input: 3, output: 4, cacheRead: 0.1, cacheWrite: 0 } },
      { id: "new", input: ["text", "image"] },
    ];
    expect(await runMode("provider_save", dirs, { ...SAVE, models })).toMatchObject({ ok: true });
    expect(await saved()).toEqual({
      providers: {
        "codex-lb": {
          baseUrl: SAVE.baseUrl,
          api: SAVE.api,
          apiKey: "sk-test",
          headers: { "X-Provider": "1" },
          compat: { supportsStrictMode: false },
          models: [
            {
              id: "gpt-a",
              headers: { "X-Model": "y" },
              compat: { supportsStrictMode: true },
              samplingParams: { temperature: 0.2 },
              reasoning: false,
              cost: { input: 3, output: 4, cacheRead: 0.1, cacheWrite: 0, tiers },
            },
            { id: "new", input: ["text", "image"] },
          ],
        },
      },
    });
  });

  it.each([
    { name: 5 },
    { reasoning: "yes" },
    { thinkingLevelMap: { low: 1 } },
    { thinkingLevelMap: { turbo: "turbo" } },
    { input: ["audio"] },
    { contextWindow: -1 },
    { maxTokens: "big" },
    { cost: { input: 1, output: 2 } },
  ])("rejects the model field %j without writing", async (field) => {
    await writeFile(modelsPath, "{}");
    const result = await runMode("provider_save", dirs, { ...SAVE, models: [{ id: "gpt-a", ...field }] });
    expect(result).toMatchObject({ ok: false, code: "bad_params" });
    expect(await readFile(modelsPath, "utf8")).toBe("{}");
  });

  it.each([{ models: ["gpt-a"] }, { models: [{ id: "a" }, { id: "a" }] }, { models: undefined }])(
    "rejects malformed models %j",
    async (params) => {
      expect(await runMode("provider_save", dirs, { ...SAVE, ...params })).toMatchObject({ ok: false, code: "bad_params" });
    },
  );

  it("saves a keyless provider with a placeholder key Pi accepts, which the editor sees as no key", async () => {
    expect(await runMode("provider_save", dirs, { ...SAVE, apiKey: "" })).toMatchObject({ ok: true });
    expect(await saved()).toMatchObject({ providers: { "codex-lb": { apiKey: "none" } } });
    const status = await runMode("auth_status", dirs, {});
    expect(status.data).toMatchObject({ custom: [expect.objectContaining({ id: "codex-lb", apiKey: "" })] });
  });

  it("rejects invalid models.json without writing", async () => {
    await writeFile(modelsPath, "{ not json");
    expect(await runMode("provider_save", dirs, SAVE)).toEqual({
      type: "pipkin_result",
      ok: false,
      code: "models_invalid",
      error: "models.json is not valid JSON. Ask the agent to fix it.",
      i18n: { key: "models_invalid" },
    });
    expect(await readFile(modelsPath, "utf8")).toBe("{ not json");
  });

  it.each(["Bad", "-lead", "has space", "a".repeat(41), ""])("rejects the provider id %j", async (id) => {
    expect(await runMode("provider_save", dirs, { ...SAVE, id })).toMatchObject({ ok: false, code: "bad_id" });
  });

  it("rejects a built-in provider id", async () => {
    expect(await runMode("provider_save", dirs, { ...SAVE, id: "anthropic" })).toMatchObject({ ok: false, code: "builtin_id" });
  });

  it("removes a provider and keeps the rest", async () => {
    await runMode("provider_save", dirs, SAVE);
    await runMode("provider_save", dirs, { ...SAVE, id: "second" });
    expect(await runMode("provider_remove", dirs, { id: "codex-lb" })).toMatchObject({ ok: true });
    expect(await saved()).toEqual({ providers: { second: expect.any(Object) } });
  });
});
