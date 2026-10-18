import { writeFile } from "node:fs/promises";
import { createServer, type Server } from "node:http";
import path from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { JsonObject } from "../src/framing.js";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

/** Pi 1.3.0's built-in `openai` entry for gpt-5.5 (other providers list different windows and maps). */
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

/** Endpoints that fail the way real servers do, keyed by `/models` path. */
const FAILURES: Record<string, { status: number; type: string; body: string }> = {
  "/openai/models": {
    status: 403,
    type: "application/json",
    body: JSON.stringify({ error: { message: "Invalid API key", type: "authentication_error" } }),
  },
  "/site/models": { status: 404, type: "text/html", body: "<!DOCTYPE html><html><body>Not Found</body></html>" },
  "/busy/models": { status: 429, type: "application/json", body: JSON.stringify({ detail: "Slow down" }) },
  "/down/models": { status: 502, type: "text/plain", body: "upstream unavailable\n" },
  "/teapot/models": { status: 418, type: "text/plain", body: "" },
  "/page/models": { status: 200, type: "text/html", body: "<html><body>Welcome</body></html>" },
};

describe("provider_models and model_defaults", () => {
  let dirs: Dirs;
  let server: Server;
  let origin: string;
  let baseUrl: string;
  let entries: unknown[] = [];

  beforeAll(async () => {
    server = createServer((request, response) => {
      if (request.url === "/reset/models") {
        // Headers and the start of a body, then the connection drops.
        response.writeHead(200, { "content-type": "application/json" });
        response.write('{"data":[', () => request.socket.destroy());
        return;
      }
      const failure = FAILURES[request.url ?? ""];
      if (failure !== undefined) {
        response.writeHead(failure.status, { "content-type": failure.type });
        response.end(failure.body);
        return;
      }
      const authorized = request.headers.authorization === "Bearer sk-test" && request.url === "/v1/models";
      response.writeHead(authorized ? 200 : 401, { "content-type": "application/json" });
      response.end(JSON.stringify(authorized ? { object: "list", data: entries } : { error: "nope" }));
    });
    const listening = Promise.withResolvers<void>();
    server.listen(0, "127.0.0.1", listening.resolve);
    await listening.promise;
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("No server address");
    origin = `http://127.0.0.1:${address.port}`;
    baseUrl = `${origin}/v1/`;
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

  it.each([
    ["/v1", "unauthorized", "HTTP 401: nope"],
    ["/openai", "unauthorized", "HTTP 403: Invalid API key"],
    ["/site", "not_found", "HTTP 404"],
    ["/busy", "rate_limited", "HTTP 429: Slow down"],
    ["/down", "server_error", "HTTP 502: upstream unavailable"],
    ["/teapot", "http_error", "HTTP 418"],
  ])("reports HTTP errors from %s by cause, with what the server said", async (prefix, code, error) => {
    const result = await runMode("provider_models", dirs, { baseUrl: origin + prefix, apiKey: "wrong", api: "openai-responses" });
    expect(result).toMatchObject({ ok: false, code, error });
  });

  it("rejects a web page in place of the models list", async () => {
    const result = await runMode("provider_models", dirs, { baseUrl: `${origin}/page`, apiKey: "k", api: "openai-responses" });
    const url = `${origin}/page/models`;
    expect(result).toMatchObject({ ok: false, code: "bad_response", error: `${url} returned a web page, not JSON`, i18n: { key: "provider_web_page", args: { url } } });
  });

  it.each(["api.example.com/v1", "ftp://example.com/v1", "https://"])("rejects %s as a base URL", async (url) => {
    const result = await runMode("provider_models", dirs, { baseUrl: url, apiKey: "k", api: "openai-responses" });
    expect(result).toMatchObject({ ok: false, code: "bad_url" });
  });

  it("reports an endpoint nothing listens on as unreachable", async () => {
    const closed = createServer();
    const listening = Promise.withResolvers<void>();
    closed.listen(0, "127.0.0.1", listening.resolve);
    await listening.promise;
    const address = closed.address();
    if (address === null || typeof address === "string") throw new Error("No server address");
    const stopped = Promise.withResolvers<void>();
    closed.close(() => stopped.resolve());
    await stopped.promise;
    const url = `http://127.0.0.1:${address.port}/v1`;
    expect(await runMode("provider_models", dirs, { baseUrl: url, apiKey: "k", api: "openai-responses" })).toMatchObject({
      ok: false,
      code: "network",
      error: expect.stringContaining(`Could not reach ${url}`),
    });
  });

  it("reports a server that hangs up partway through its answer as unreachable", async () => {
    const result = await runMode("provider_models", dirs, { baseUrl: `${origin}/reset`, apiKey: "k", api: "openai-responses" });
    expect(result).toMatchObject({ ok: false, code: "network", error: expect.stringContaining(`Could not reach ${origin}/reset`) });
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
