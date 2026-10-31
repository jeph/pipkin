import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runOp } from "./harness.js";
import type { Api, Message, Model } from "@earendil-works/pi-ai";
import { clampMaxTokensToContext } from "@earendil-works/pi-ai/api/simple-options";
import { normalizeContext } from "@earendil-works/pi-ai/utils/transcript";

const FREE_COST = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 };

const SYNC = {
  baseUrl: "http://127.0.0.1:47325/v1",
  apiKey: "local-key",
  models: [
    { id: "qwen3-0.6b", name: "Qwen3 0.6B", reasoning: true, contextWindow: 32_768, maxTokens: 4096 },
    { id: "gemma-4-e2b", name: "Gemma 4 E2B", reasoning: true, contextWindow: 16_384, maxTokens: 2048 },
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
    expect(await runOp("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true });

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
    expect(await runOp("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true });
    expect((await json("settings.json")).compaction).toEqual({
      enabled: true,
      modelOverrides: {
        "openai/gpt": keep,
        "pipkin-local/qwen3-0.6b": { reserveTokens: 8192, keepRecentTokens: 2048 },
        "pipkin-local/gemma-4-e2b": { reserveTokens: 6144, keepRecentTokens: 1024 },
      },
    });
  });

  it("compacts before pi-ai would cut a reply below the model's maxTokens", async () => {
    await runOp("local_models_sync", dirs, SYNC);
    const overrides = (await json("settings.json")).compaction as { modelOverrides: Record<string, { reserveTokens: number }> };
    for (const model of SYNC.models) {
      const { reserveTokens } = overrides.modelOverrides[`pipkin-local/${model.id}`]!;
      // Pi compacts once the prompt passes the window less reserveTokens, so this is the largest prompt a reply gets.
      const prompt = model.contextWindow - reserveTokens;
      const usage = { input: prompt, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: prompt, cost: FREE_COST };
      const message = { role: "assistant", content: [], usage, stopReason: "stop", timestamp: 0 } as unknown as Message;
      const context = normalizeContext({ messages: [message] });
      expect(clampMaxTokensToContext(model as unknown as Model<Api>, context, model.maxTokens)).toBe(model.maxTokens);
    }
  });

  it("removes the provider and its overrides when no models are left", async () => {
    await runOp("local_models_sync", dirs, SYNC);
    expect(await runOp("local_models_sync", dirs, { ...SYNC, models: [] })).toMatchObject({ ok: true });
    expect(await json("models.json")).toEqual({ providers: {} });
    expect((await json("settings.json")).compaction).toEqual({});
  });

  it("keeps the local models in scope when enabledModels limits the list", async () => {
    await writeFile(path.join(dirs.configDir, "settings.json"), JSON.stringify({ enabledModels: ["anthropic/*"] }));
    expect(await runOp("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true });
    expect((await json("settings.json")).enabledModels).toEqual(["anthropic/*", "pipkin-local/*"]);
  });

  it("answers changed only when it wrote something, and leaves unchanged files alone", async () => {
    await writeFile(path.join(dirs.configDir, "settings.json"), JSON.stringify({ enabledModels: ["anthropic/*"] }));
    expect(await runOp("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true, data: { changed: true } });
    // A trailing space a rewrite would drop shows whether models.json was written again.
    const models = `${(await readFile(path.join(dirs.configDir, "models.json"), "utf8")).trimEnd()} \n`;
    await writeFile(path.join(dirs.configDir, "models.json"), models);
    const settings = await readFile(path.join(dirs.configDir, "settings.json"), "utf8");

    expect(await runOp("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true, data: { changed: false } });
    expect(await readFile(path.join(dirs.configDir, "models.json"), "utf8")).toBe(models);
    expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe(settings);

    const fewer = { ...SYNC, models: SYNC.models.slice(1) };
    expect(await runOp("local_models_sync", dirs, fewer)).toMatchObject({ ok: true, data: { changed: true } });
    const none = { ...SYNC, models: [] };
    expect(await runOp("local_models_sync", dirs, none)).toMatchObject({ ok: true, data: { changed: true } });
    expect(await runOp("local_models_sync", dirs, none)).toMatchObject({ ok: true, data: { changed: false } });
  });

  it("answers changed when settings.json lost the scope and overrides, as a restore can", async () => {
    const scoped = JSON.stringify({ enabledModels: ["anthropic/*"] });
    await writeFile(path.join(dirs.configDir, "settings.json"), scoped);
    await runOp("local_models_sync", dirs, SYNC);
    await writeFile(path.join(dirs.configDir, "settings.json"), scoped);
    expect(await runOp("local_models_sync", dirs, SYNC)).toMatchObject({ ok: true, data: { changed: true } });
    expect(await json("settings.json")).toMatchObject({
      enabledModels: ["anthropic/*", "pipkin-local/*"],
      compaction: { modelOverrides: { "pipkin-local/qwen3-0.6b": { reserveTokens: 8192 } } },
    });
  });
});
