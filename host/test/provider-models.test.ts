import { writeFile } from "node:fs/promises";
import { createServer, type Server } from "node:http";
import path from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { JsonObject } from "../src/framing.js";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

/** Pi 1.1.0's built-in `openai` entry for gpt-5.5 (other providers list different windows and maps). */
const GPT_5_5 = {
  id: "gpt-5.5",
  name: "GPT-5.5",
  reasoning: true,
  thinkingLevelMap: { off: "none", minimal: null, low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: null },
  input: ["text", "image"],
  contextWindow: 272_000,
  maxTokens: 128_000,
  cost: { input: 5, output: 30, cacheRead: 0.5, cacheWrite: 0 },
};

/** A codex-lb `/models` entry, as served by the real proxy. */
function codexLbEntry(id: string, contextLength: number): JsonObject {
  return {
    id,
    context_length: contextLength,
    supports_reasoning: true,
    supports_images: true,
    api_types: ["chat_completions"],
    capabilities: { context_length: contextLength, supports_reasoning: true, supports_images: true, input_modalities: ["text", "image"] },
    metadata: {
      display_name: "GPT-6.1-Sol",
      context_window: contextLength,
      max_output_tokens: null,
      input_modalities: ["text", "image"],
      supported_reasoning_levels: ["low", "medium", "high", "xhigh", "max", "ultra"].map((effort) => ({ effort })),
      default_reasoning_level: "low",
    },
  };
}

const OPENROUTER_ENTRY = {
  id: "acme/model-x",
  name: "Acme: Model X",
  context_length: 131_072,
  architecture: { input_modalities: ["text", "image"], output_modalities: ["text"] },
  pricing: { prompt: "0.000003", completion: "0.000015", input_cache_read: "0.0000003" },
  top_provider: { context_length: 131_072, max_completion_tokens: 32_768 },
  supported_parameters: ["tools", "reasoning", "max_tokens"],
};

function success(models: unknown[]): JsonObject {
  return { type: "pipkin_result", ok: true, data: { models } };
}

describe("provider_models and model_defaults", () => {
  let dirs: Dirs;
  let server: Server;
  let baseUrl: string;
  let entries: unknown[] = [];

  beforeAll(async () => {
    server = createServer((request, response) => {
      const authorized = request.headers.authorization === "Bearer sk-test" && request.url === "/v1/models";
      response.writeHead(authorized ? 200 : 401, { "content-type": "application/json" });
      response.end(JSON.stringify(authorized ? { object: "list", data: entries } : { error: "nope" }));
    });
    const listening = Promise.withResolvers<void>();
    server.listen(0, "127.0.0.1", listening.resolve);
    await listening.promise;
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("No server address");
    baseUrl = `http://127.0.0.1:${address.port}/v1/`;
  });

  afterAll(() => {
    server.close();
  });

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  /** Runs provider_models against the local endpoint and returns the whole `pipkin_result`. */
  async function fetchModels(): Promise<JsonObject> {
    return await runMode("provider_models", dirs, { baseUrl, apiKey: "sk-test", api: "openai-responses" });
  }

  it("detects capabilities and fills the rest from Pi's catalogue, sorted by id", async () => {
    entries = [
      { id: "mystery-model", object: "model" },
      codexLbEntry("codex-sol", 272_000),
      { id: "gpt-5.5", object: "model" },
      OPENROUTER_ENTRY,
      { id: "text-only", supports_images: false, supports_reasoning: false },
      { id: "no-levels", supports_reasoning: false, metadata: { supported_reasoning_levels: [{ effort: "ultra" }] } },
      { object: "model" },
    ];
    expect(await fetchModels()).toEqual(success([
      {
        id: "acme/model-x",
        name: "Acme: Model X",
        reasoning: true,
        input: ["text", "image"],
        contextWindow: 131_072,
        maxTokens: 32_768,
        cost: { input: 3, output: 15, cacheRead: 0.3, cacheWrite: 0 },
      },
      {
        id: "codex-sol",
        name: "GPT-6.1-Sol",
        reasoning: true,
        thinkingLevelMap: { off: null, minimal: null, low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: "max" },
        input: ["text", "image"],
        contextWindow: 272_000,
      },
      GPT_5_5,
      { id: "mystery-model" },
      { id: "no-levels", reasoning: false },
      { id: "text-only", reasoning: false, input: ["text"] },
    ]));
  });

  it("keeps endpoint values over the catalogue, field by field", async () => {
    entries = [codexLbEntry("gpt-6.1-sol", 200_000)];
    expect(await fetchModels()).toEqual(success([{
      id: "gpt-6.1-sol",
      name: "GPT-6.1-Sol",
      reasoning: true,
      thinkingLevelMap: { off: null, minimal: null, low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: "max" },
      input: ["text", "image"],
      contextWindow: 200_000,
      maxTokens: 128_000,
      cost: expect.objectContaining({ input: expect.any(Number), output: expect.any(Number) }),
    }]));
  });

  it("reports HTTP errors", async () => {
    const result = await runMode("provider_models", dirs, { baseUrl, apiKey: "wrong", api: "openai-responses" });
    expect(result).toMatchObject({ ok: false, code: "http_error", error: 'HTTP 401: {"error":"nope"}' });
  });

  it("returns catalogue defaults in request order, ignoring models.json", async () => {
    const custom = {
      baseUrl: "https://example.test/v1",
      api: "openai-responses",
      apiKey: "k",
      models: [{ id: "gpt-5.5", contextWindow: 5 }, { id: "lb-only", name: "LB only", contextWindow: 9 }],
    };
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers: { "my-lb": custom } }));
    const result = await runMode("model_defaults", dirs, { ids: ["mystery", "gpt-5.5", "lb-only", "claude-opus-4-8"], api: "openai-responses" });
    expect(result).toEqual(success([
      { id: "mystery" },
      GPT_5_5,
      { id: "lb-only" },
      expect.objectContaining({ id: "claude-opus-4-8", name: "Claude Opus 4.8", reasoning: true, contextWindow: 1_000_000 }),
    ]));
  });

  it("rejects model_defaults without ids", async () => {
    expect(await runMode("model_defaults", dirs, { api: "openai-responses" })).toMatchObject({ ok: false, code: "bad_params" });
  });
});
