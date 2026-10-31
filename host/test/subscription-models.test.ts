import { readFile, writeFile } from "node:fs/promises";
import { createServer, type Server } from "node:http";
import path from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runOp } from "./harness.js";

/** A Kimi Code `/models` entry for a model Pi 1.4.0 doesn't ship, as the real endpoint serves it. */
const K3_1_PRO = {
  id: "k3.1-pro",
  object: "model",
  display_name: "K3.1 Pro",
  context_length: 1_048_576,
  supports_reasoning: true,
  supports_image_in: true,
  supports_thinking_type: "only",
  modalities: { input: ["text", "image", "video"], output: ["text"] },
  reasoning: { type: "only", effort: { valid: ["low", "high", "max"], default: "high" } },
};

describe("subscription_models", () => {
  let dirs: Dirs;
  let server: Server;
  let origin: string;
  const requests: string[] = [];

  beforeAll(async () => {
    server = createServer((request, response) => {
      requests.push(`${request.headers.authorization} ${request.url}`);
      const authorized = request.headers.authorization === "Bearer key-123";
      const data =
        request.url === "/kimi/models"
          ? [{ id: "k3", object: "model", display_name: "K3 (served)" }, K3_1_PRO]
          : request.url === "/openrouter/models/user"
            ? [{ id: "acme/model-x", name: "Acme: Model X", context_length: 131_072, supported_parameters: ["tools"] }]
            : undefined;
      response.writeHead(authorized && data !== undefined ? 200 : 401, { "content-type": "application/json" });
      response.end(JSON.stringify(authorized && data !== undefined ? { object: "list", data } : { error: { message: "Invalid Authentication" } }));
    });
    const listening = Promise.withResolvers<void>();
    server.listen(0, "127.0.0.1", listening.resolve);
    await listening.promise;
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("No server address");
    origin = `http://127.0.0.1:${address.port}`;
  });

  afterAll(() => {
    server.close();
  });

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    requests.length = 0;
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  /** Points the built-in providers at the local server and stores `key` for them. */
  async function connect(key: string): Promise<void> {
    const providers = { "kimi-coding": { baseUrl: `${origin}/kimi` }, openrouter: { baseUrl: `${origin}/openrouter` } };
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers }));
    const credential = { type: "api_key", key };
    await writeFile(path.join(dirs.configDir, "auth.json"), JSON.stringify({ "kimi-coding": credential, openrouter: credential }));
  }

  it("lists the key's models, with Pi's settings for the ones Pi knows and the endpoint's for the rest", async () => {
    await connect("key-123");
    const result = await runOp("subscription_models", dirs, { providerId: "kimi-coding" });
    expect(requests).toEqual(["Bearer key-123 /kimi/models"]);
    expect(result).toMatchObject({
      ok: true,
      data: {
        models: [
          expect.objectContaining({ id: "k3", name: "Kimi K3", contextWindow: 1_048_576, maxTokens: 131_072 }),
          {
            id: "k3.1-pro",
            name: "K3.1 Pro",
            reasoning: true,
            thinkingLevelMap: { off: null, minimal: null, low: "low", medium: null, high: "high", xhigh: null, max: "max" },
            input: ["text", "image"],
            contextWindow: 1_048_576,
          },
        ],
      },
    });
  });

  it("asks OpenRouter for the models its key may use", async () => {
    await connect("key-123");
    const result = await runOp("subscription_models", dirs, { providerId: "openrouter" });
    expect(requests).toEqual(["Bearer key-123 /openrouter/models/user"]);
    expect(result).toMatchObject({ ok: true, data: { models: [expect.objectContaining({ id: "acme/model-x", name: "Acme: Model X" })] } });
  });

  it("reports a key the provider refuses", async () => {
    await connect("revoked");
    const result = await runOp("subscription_models", dirs, { providerId: "kimi-coding" });
    expect(result).toMatchObject({ ok: false, code: "unauthorized", error: "HTTP 401: Invalid Authentication" });
  });

  it("needs a stored key", async () => {
    const result = await runOp("subscription_models", dirs, { providerId: "kimi-coding" });
    expect(result).toMatchObject({ ok: false, code: "no_key", i18n: { key: "subscription_no_key" } });
  });

  it("has no list for Gemini, whose models come from Pi's catalogue", async () => {
    expect(await runOp("subscription_models", dirs, { providerId: "google" })).toEqual({ type: "pipkin_result", ok: true, data: {} });
  });

  it.each(["anthropic", "vercel-ai-gateway"])("refuses %s, which Pipkin doesn't connect with a key", async (providerId) => {
    expect(await runOp("subscription_models", dirs, { providerId })).toMatchObject({ ok: false, code: "bad_params" });
  });

  it("gives a model Pi doesn't ship the compat that its provider's like models share when it is saved", async () => {
    const levels = { off: null, minimal: null, low: "low", medium: null, high: "high", xhigh: null, max: "max" };
    const models = [{ id: "k3" }, { id: "k3.1-pro", name: "K3.1 Pro", reasoning: true, thinkingLevelMap: levels }];
    expect(await runOp("subscription_save", dirs, { providerId: "kimi-coding", enabled: ["k3", "k3.1-pro"], models })).toMatchObject({ ok: true });
    const document = JSON.parse(await readFile(path.join(dirs.configDir, "models.json"), "utf8")) as unknown;
    expect(document).toMatchObject({
      providers: {
        "kimi-coding": {
          models: [
            {
              id: "k3.1-pro",
              name: "K3.1 Pro",
              compat: expect.objectContaining({
                maxTokensField: "max_tokens",
                thinkingFormat: "openai",
                requiresReasoningContentOnAssistantMessages: true,
              }),
            },
          ],
        },
      },
    });
  });
});
