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

  it.each(["google", "zai", "kimi-coding"])("stores the API key the app enters for %s", async (providerId) => {
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

  it("adds the provider's models to a scope that would hide them", async () => {
    const settingsPath = path.join(dirs.configDir, "settings.json");
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["anthropic/*"] }));
    expect((await login(dirs, "zai", "key-123")).result).toMatchObject({ ok: true });
    const settings: unknown = JSON.parse(await readFile(settingsPath, "utf8"));
    expect(settings).toMatchObject({ enabledModels: ["anthropic/*", "zai/*"] });
  });

  it("rejects a provider Pipkin does not connect", async () => {
    expect(await runMode("login", dirs, { providerId: "openrouter" })).toMatchObject({ ok: false, code: "bad_params" });
  });
});
