import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

const SYNC = {
  baseUrl: "http://127.0.0.1:47325/v1",
  apiKey: "local-key",
  models: [
    { id: "qwen3-0.6b", name: "Qwen3 0.6B", reasoning: true, contextWindow: 32_768, maxTokens: 8192 },
    { id: "gemma-4-e2b", name: "Gemma 4 E2B", reasoning: true, contextWindow: 4096, maxTokens: 2048 },
  ],
};

describe("local_models_sync mode", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  async function json(file: string): Promise<Record<string, unknown>> {
    return JSON.parse(await readFile(path.join(dirs.configDir, file), "utf8")) as Record<string, unknown>;
  }

  it("serves the models under pipkin-local with session affinity, free, text only, beside other providers", async () => {
    const other = { baseUrl: "http://localhost:11434/v1", api: "openai-completions", apiKey: "x", models: [{ id: "llama" }] };
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers: { ollama: other } }));
    expect(await runMode("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true });

    const models = await json("models.json");
    expect(models).toMatchObject({ providers: { ollama: other } });
    const local = (models.providers as Record<string, Record<string, unknown>>)["pipkin-local"];
    expect(local).toMatchObject({
      baseUrl: SYNC.baseUrl,
      api: "openai-completions",
      apiKey: "local-key",
      compat: { sendSessionAffinityHeaders: true },
    });
    expect(local?.models).toEqual(
      SYNC.models.map((model) => ({
        ...model,
        input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      })),
    );
  });

  it("sizes compaction to each model's context and keeps other models' overrides", async () => {
    const keep = { reserveTokens: 1000, keepRecentTokens: 2000 };
    await writeFile(
      path.join(dirs.configDir, "settings.json"),
      JSON.stringify({ compaction: { enabled: true, modelOverrides: { "openai/gpt": keep, "pipkin-local/gone": keep } } }),
    );
    expect(await runMode("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true });
    expect((await json("settings.json")).compaction).toEqual({
      enabled: true,
      modelOverrides: {
        "openai/gpt": keep,
        "pipkin-local/qwen3-0.6b": { reserveTokens: 8192, keepRecentTokens: 8192 },
        "pipkin-local/gemma-4-e2b": { reserveTokens: 1024, keepRecentTokens: 1024 },
      },
    });
  });

  it("removes the provider and its overrides when no models are left", async () => {
    await runMode("local_models_sync", dirs, SYNC);
    expect(await runMode("local_models_sync", dirs, { ...SYNC, models: [] })).toMatchObject({ ok: true });
    expect(await json("models.json")).toEqual({ providers: {} });
    expect((await json("settings.json")).compaction).toEqual({});
  });

  it("keeps the local models in scope when enabledModels limits the list", async () => {
    await writeFile(path.join(dirs.configDir, "settings.json"), JSON.stringify({ enabledModels: ["anthropic/*"] }));
    expect(await runMode("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true });
    expect((await json("settings.json")).enabledModels).toEqual(["anthropic/*", "pipkin-local/*"]);
  });
});
