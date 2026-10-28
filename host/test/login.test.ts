import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { JsonObject } from "../src/framing.js";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode, startHost } from "./harness.js";

/** Runs `login` for `providerId`, answering every prompt with `answer`; returns the prompts and the result. */
async function login(dirs: Dirs, providerId: string, answer: string): Promise<{ prompts: unknown[]; result: JsonObject }> {
  const { run } = await startHost("login", dirs);
  await run.next();
  run.send({ type: "pipkin_request", params: { providerId } });
  const prompts: unknown[] = [];
  for (;;) {
    const message = await run.next();
    if (message === undefined) throw new Error("login closed without a result");
    if (message.type === "pipkin_auth_prompt") {
      prompts.push(message.prompt);
      run.send({ type: "pipkin_auth_response", id: message.id, value: answer });
    }
    if (message.type === "pipkin_result") {
      await run.exited;
      return { prompts, result: message };
    }
  }
}

describe("login", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it.each(["google", "zai", "kimi-coding", "openrouter"])("stores the API key the app enters for %s", async (providerId) => {
    const { prompts, result } = await login(dirs, providerId, "key-123");
    expect(result).toMatchObject({ ok: true });
    expect(prompts).toEqual([expect.objectContaining({ type: "secret" })]);
    const auth: unknown = JSON.parse(await readFile(path.join(dirs.configDir, "auth.json"), "utf8"));
    expect(auth).toMatchObject({ [providerId]: { type: "api_key", key: "key-123" } });
    const status = await runMode("auth_status", dirs, {});
    expect(status.data).toMatchObject({
      builtin: expect.arrayContaining([expect.objectContaining({ id: providerId, configured: true, type: "api_key" })]),
    });
  });

  it("adds the models of a provider without a models list to a scope that would hide them", async () => {
    const settingsPath = path.join(dirs.configDir, "settings.json");
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["anthropic/*"] }));
    expect((await login(dirs, "google", "key-123")).result).toMatchObject({ ok: true });
    const settings: unknown = JSON.parse(await readFile(settingsPath, "utf8"));
    expect(settings).toMatchObject({ enabledModels: ["anthropic/*", "google/*"] });
  });

  it.each(["zai", "kimi-coding", "openrouter"])("leaves %s's models for the user to pick from its list", async (providerId) => {
    const settingsPath = path.join(dirs.configDir, "settings.json");
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["openai-codex/*", "*sonnet*"] }));
    expect((await login(dirs, providerId, "key-123")).result).toMatchObject({ ok: true });
    expect((await runMode("auth_status", dirs, {})).data).toMatchObject({
      builtin: expect.arrayContaining([
        expect.objectContaining({ id: providerId, enabled: [] }),
        expect.objectContaining({ id: "openai-codex", enabled: expect.arrayContaining([expect.any(String)]) }),
      ]),
    });
  });

  it("keeps every model of a provider whose key is entered again", async () => {
    const settingsPath = path.join(dirs.configDir, "settings.json");
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["openai-codex/*", "kimi-coding/*"] }));
    expect((await login(dirs, "kimi-coding", "key-123")).result).toMatchObject({ ok: true });
    expect(JSON.parse(await readFile(settingsPath, "utf8"))).toMatchObject({ enabledModels: ["openai-codex/*", "kimi-coding/*"] });
  });

  it("keeps an empty scope from showing a listed provider's models", async () => {
    expect((await login(dirs, "kimi-coding", "key-123")).result).toMatchObject({ ok: true });
    expect((await runMode("auth_status", dirs, {})).data).toMatchObject({
      builtin: expect.arrayContaining([
        expect.objectContaining({ id: "kimi-coding", enabled: [] }),
        expect.objectContaining({ id: "google", enabled: expect.arrayContaining([expect.any(String)]) }),
      ]),
    });
  });

  it("narrows a glob that would show OpenRouter's models and keeps the ones picked for it", async () => {
    const settingsPath = path.join(dirs.configDir, "settings.json");
    expect((await login(dirs, "google", "key-123")).result).toMatchObject({ ok: true });
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["*gemma-4-31b*"] }));
    expect((await login(dirs, "openrouter", "key-123")).result).toMatchObject({ ok: true });
    expect((await runMode("auth_status", dirs, {})).data).toMatchObject({
      builtin: expect.arrayContaining([
        expect.objectContaining({ id: "openrouter", enabled: [] }),
        expect.objectContaining({ id: "google", enabled: ["gemma-4-31b-it"] }),
      ]),
    });

    // Anthropic has no key, so Pi would read `anthropic/*` as OpenRouter's `anthropic/…` ids.
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["anthropic/*", "openrouter/google/gemma-4-31b-it:free"] }));
    expect((await login(dirs, "openrouter", "key-123")).result).toMatchObject({ ok: true });
    expect(JSON.parse(await readFile(settingsPath, "utf8"))).toMatchObject({ enabledModels: ["openrouter/google/gemma-4-31b-it:free"] });
  });

  it("drops a signed-out provider's patterns that would show OpenRouter's copies", async () => {
    const settingsPath = path.join(dirs.configDir, "settings.json");
    expect((await login(dirs, "google", "key-123")).result).toMatchObject({ ok: true });
    expect((await login(dirs, "openrouter", "key-123")).result).toMatchObject({ ok: true });
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["google/*", "openrouter/google/gemma-4-31b-it:free"] }));
    expect(await runMode("logout", dirs, { providerId: "google" })).toMatchObject({ ok: true });
    expect(JSON.parse(await readFile(settingsPath, "utf8"))).toMatchObject({ enabledModels: ["openrouter/google/gemma-4-31b-it:free"] });
  });

  it("rejects a provider Pipkin does not connect", async () => {
    expect(await runMode("login", dirs, { providerId: "vercel-ai-gateway" })).toMatchObject({ ok: false, code: "bad_params" });
  });
});
