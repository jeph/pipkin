import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { isObject, type JsonObject } from "../src/framing.js";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

const LB = { baseUrl: "https://example.test/v1", api: "openai-responses", apiKey: "k", models: [{ id: "lb-1" }] };
const TIERS = [{ inputTokensAbove: 200_000, input: 10, output: 50, cacheRead: 1, cacheWrite: 12.5 }];

async function readJson(file: string): Promise<unknown> {
  return JSON.parse(await readFile(file, "utf8"));
}

async function writeJson(file: string, value: unknown): Promise<void> {
  await writeFile(file, JSON.stringify(value));
}

describe("subscription model settings and scope", () => {
  let dirs: Dirs;
  let modelsPath: string;
  let settingsPath: string;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    modelsPath = path.join(dirs.configDir, "models.json");
    settingsPath = path.join(dirs.configDir, "settings.json");
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  async function status(): Promise<JsonObject> {
    const result = await runMode("auth_status", dirs, {});
    if (!isObject(result.data)) throw new Error(`auth_status failed: ${JSON.stringify(result)}`);
    return result.data;
  }

  /** Every model id Pi currently offers for `providerId`. */
  async function providerIds(providerId: string): Promise<string[]> {
    const { builtin } = await status();
    const entry = Array.isArray(builtin) ? builtin.find((provider) => isObject(provider) && provider.id === providerId) : undefined;
    if (!isObject(entry) || !Array.isArray(entry.models)) throw new Error(`No ${providerId} in auth_status`);
    return entry.models.flatMap((model) => (isObject(model) && typeof model.id === "string" ? [model.id] : []));
  }

  async function save(providerId: string, enabled: string[], models: JsonObject[]): Promise<JsonObject> {
    return await runMode("subscription_save", dirs, { providerId, enabled, models });
  }

  async function enabledModels(): Promise<unknown> {
    const settings = await readJson(settingsPath);
    return isObject(settings) ? settings.enabledModels : undefined;
  }

  it("writes only overrides that differ from Pi's catalogue and keeps what Pipkin doesn't manage", async () => {
    await writeJson(modelsPath, {
      providers: {
        anthropic: {
          headers: { "X-P": "1" },
          modelOverrides: {
            "claude-opus-4-8": { headers: { "X-M": "1" }, name: "Old", cost: { input: 1, tiers: TIERS } },
            "claude-haiku-4-5": { maxTokens: 5 },
            "claude-sonnet-5": { name: "Not in this save" },
          },
        },
      },
    });
    await writeFile(settingsPath, '{"theme":"dark"}');
    const opus = {
      id: "claude-opus-4-8",
      name: "Claude Opus 4.8",
      reasoning: true,
      input: ["text", "image"],
      contextWindow: 200_000,
      cost: { input: 5, output: 25, cacheRead: 0.5, cacheWrite: 6.25 },
    };
    const all = await providerIds("anthropic");
    expect(await save("anthropic", all, [opus, { id: "claude-haiku-4-5" }])).toMatchObject({ ok: true });
    expect(await readJson(modelsPath)).toEqual({
      providers: {
        anthropic: {
          headers: { "X-P": "1" },
          modelOverrides: {
            "claude-opus-4-8": { headers: { "X-M": "1" }, contextWindow: 200_000, cost: { tiers: TIERS } },
            "claude-sonnet-5": { name: "Not in this save" },
          },
        },
      },
    });
    // Every model stays enabled and there was no scope, so settings.json is untouched.
    expect(await readFile(settingsPath, "utf8")).toBe('{"theme":"dark"}');
  });

  it("removes the provider entry once nothing is overridden", async () => {
    await writeJson(modelsPath, { providers: { anthropic: { modelOverrides: { "claude-opus-4-8": { contextWindow: 5 } } } } });
    expect(await save("anthropic", await providerIds("anthropic"), [{ id: "claude-opus-4-8" }])).toMatchObject({ ok: true });
    expect(await readJson(modelsPath)).toEqual({ providers: {} });
  });

  it("adds and drops extra models and reports them with their effective settings", async () => {
    await writeJson(modelsPath, {
      providers: { anthropic: { models: [{ id: "my-extra", name: "Old", samplingParams: { temperature: 0.1 } }, { id: "gone" }] } },
    });
    const catalogue = (await providerIds("anthropic")).filter((id) => id !== "my-extra" && id !== "gone");
    const models = [{ id: "my-extra", contextWindow: 5000 }, { id: "new-extra", name: "New" }];
    expect(await save("anthropic", [...catalogue, "my-extra", "new-extra"], models)).toMatchObject({ ok: true });
    expect(await readJson(modelsPath)).toEqual({
      providers: {
        anthropic: { models: [{ id: "my-extra", samplingParams: { temperature: 0.1 }, contextWindow: 5000 }, { id: "new-extra", name: "New" }] },
      },
    });
    const { builtin } = await status();
    expect(builtin).toContainEqual(
      expect.objectContaining({
        id: "anthropic",
        extraIds: ["my-extra", "new-extra"],
        models: expect.arrayContaining([
          expect.objectContaining({ id: "new-extra", name: "New" }),
          expect.objectContaining({ id: "my-extra", contextWindow: 5000 }),
        ]),
      }),
    );
    expect(await providerIds("anthropic")).not.toContain("gone");
  });

  it("builds a scope from an empty one, default model first, keeping settings Pipkin doesn't manage", async () => {
    await writeJson(modelsPath, { providers: { "my-lb": LB } });
    await writeJson(settingsPath, { theme: "dark", defaultProvider: "my-lb", defaultModel: "lb-1" });
    expect(await save("anthropic", ["claude-opus-4-8"], [])).toMatchObject({ ok: true });
    expect(await readJson(settingsPath)).toEqual({
      theme: "dark",
      defaultProvider: "my-lb",
      defaultModel: "lb-1",
      enabledModels: ["my-lb/lb-1", "google/*", "kimi-coding/*", "openai-codex/*", "zai/*", "my-lb/*", "anthropic/claude-opus-4-8"],
    });
  });

  it("replaces a provider glob and keeps other providers' patterns and explicit entries", async () => {
    await writeJson(modelsPath, { providers: { "my-lb": LB } });
    await writeJson(settingsPath, { enabledModels: ["openai-codex/gpt-5.5", "anthropic/*", "my-lb/*"] });
    expect(await save("anthropic", ["claude-opus-4-8", "claude-sonnet-5"], [])).toMatchObject({ ok: true });
    expect(await enabledModels()).toEqual(["openai-codex/gpt-5.5", "my-lb/*", "anthropic/claude-opus-4-8", "anthropic/claude-sonnet-5"]);
  });

  it("splits a bare-id glob that also matches another provider", async () => {
    await writeJson(modelsPath, { providers: { "my-lb": { ...LB, models: [{ id: "claude-opus-4-8" }, { id: "lb-1" }] } } });
    await writeJson(settingsPath, { enabledModels: ["*opus-4-8:high", "my-lb/lb-1"] });
    expect(await save("anthropic", ["claude-sonnet-5"], [])).toMatchObject({ ok: true });
    expect(await enabledModels()).toEqual(["my-lb/claude-opus-4-8:high", "my-lb/lb-1", "anthropic/claude-sonnet-5"]);
    const data = await status();
    expect(data.scope).toEqual(["my-lb/claude-opus-4-8", "my-lb/lb-1", "anthropic/claude-sonnet-5"]);
  });

  it("goes back to provider/* when every model is enabled again", async () => {
    await writeJson(settingsPath, { enabledModels: ["anthropic/claude-opus-4-8", "openai-codex/*"] });
    expect(await save("anthropic", await providerIds("anthropic"), [])).toMatchObject({ ok: true });
    expect(await enabledModels()).toEqual(["openai-codex/*", "anthropic/*"]);
  });

  it("reports the scope, enabled ids, extra ids and overridden settings", async () => {
    await writeJson(modelsPath, {
      providers: {
        "my-lb": LB,
        anthropic: { modelOverrides: { "claude-opus-4-8": { contextWindow: 200_000 } }, models: [{ id: "my-extra" }] },
      },
    });
    await writeJson(settingsPath, { enabledModels: ["anthropic/claude-opus-4-8", "anthropic/my-extra", "my-lb/*"] });
    const data = await status();
    expect(data.scope).toEqual(["anthropic/claude-opus-4-8", "anthropic/my-extra", "my-lb/lb-1"]);
    expect(data.settingsError).toBeUndefined();
    expect(data.builtin).toEqual([
      expect.objectContaining({
        id: "anthropic",
        enabled: ["claude-opus-4-8", "my-extra"],
        extraIds: ["my-extra"],
        models: expect.arrayContaining([expect.objectContaining({ id: "claude-opus-4-8", name: "Claude Opus 4.8", contextWindow: 200_000 })]),
      }),
      expect.objectContaining({ id: "openai-codex", enabled: [], extraIds: [] }),
      expect.objectContaining({ id: "google", enabled: [], extraIds: [] }),
      expect.objectContaining({ id: "zai", enabled: [], extraIds: [] }),
      expect.objectContaining({ id: "kimi-coding", enabled: [], extraIds: [] }),
    ]);
    expect(data.custom).toEqual([expect.objectContaining({ id: "my-lb" })]);
  });

  it("reports an invalid settings.json without a scope", async () => {
    await writeFile(settingsPath, "{");
    const data = await status();
    expect(data).toMatchObject({ scope: null, settingsError: expect.any(String) });
    expect(data.builtin).toEqual(Array.from({ length: 5 }, () => expect.objectContaining({ enabled: null })));
  });

  it("rejects an invalid settings.json without writing either file", async () => {
    await writeJson(modelsPath, { providers: {} });
    await writeFile(settingsPath, "{");
    const result = await save("anthropic", ["claude-opus-4-8"], [{ id: "claude-opus-4-8", contextWindow: 5 }]);
    expect(result).toMatchObject({ ok: false, code: "settings_invalid" });
    expect(await readFile(modelsPath, "utf8")).toBe('{"providers":{}}');
    expect(await readFile(settingsPath, "utf8")).toBe("{");
  });

  it.each([
    { providerId: "openai", enabled: [], models: [] },
    { providerId: "anthropic", enabled: ["not-a-model"], models: [] },
    { providerId: "anthropic", enabled: [], models: [{ id: "claude-opus-4-8", contextWindow: "big" }] },
  ])("rejects bad params %j", async (params) => {
    expect(await runMode("subscription_save", dirs, params)).toMatchObject({ ok: false, code: "bad_params" });
  });

  it("keeps a saved custom provider in a non-empty scope, default model first", async () => {
    await writeJson(settingsPath, { enabledModels: ["anthropic/*"], defaultProvider: "anthropic", defaultModel: "claude-opus-4-8" });
    const params = { id: "my-lb", baseUrl: LB.baseUrl, api: LB.api, apiKey: "k", models: [{ id: "lb-1" }] };
    expect(await runMode("provider_save", dirs, params)).toMatchObject({ ok: true });
    expect(await enabledModels()).toEqual(["anthropic/claude-opus-4-8", "anthropic/*", "my-lb/*"]);
    // Already in scope: nothing changes.
    expect(await runMode("provider_save", dirs, params)).toMatchObject({ ok: true });
    expect(await enabledModels()).toEqual(["anthropic/claude-opus-4-8", "anthropic/*", "my-lb/*"]);
  });

  it("keeps a custom provider in scope after a subscription save", async () => {
    await writeJson(modelsPath, { providers: { "my-lb": LB } });
    await writeJson(settingsPath, { enabledModels: ["anthropic/*", "my-lb/*"] });
    expect(await save("anthropic", ["claude-opus-4-8"], [])).toMatchObject({ ok: true });
    expect((await status()).scope).toEqual(["my-lb/lb-1", "anthropic/claude-opus-4-8"]);
  });

  it("drops a removed provider's patterns", async () => {
    await writeJson(modelsPath, { providers: { "my-lb": LB } });
    await writeJson(settingsPath, { enabledModels: ["my-lb/lb-1", "anthropic/*", "my-lb/*"] });
    expect(await runMode("provider_remove", dirs, { id: "my-lb" })).toMatchObject({ ok: true });
    expect(await enabledModels()).toEqual(["anthropic/*"]);
  });
});
