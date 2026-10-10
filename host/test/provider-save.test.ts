import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

const SAVE = {
  id: "codex-lb",
  baseUrl: "https://example.test/v1",
  api: "openai-responses",
  apiKey: "sk-test",
  images: true,
  models: ["gpt-a", "gpt-b"],
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

  it("adds the provider and keeps other providers and keys", async () => {
    const other = { baseUrl: "http://localhost:11434/v1", api: "openai-completions", apiKey: "x", models: [{ id: "llama" }] };
    await writeFile(modelsPath, JSON.stringify({ extra: true, providers: { local: other } }));
    expect(await runMode("provider_save", dirs, SAVE)).toEqual({ type: "pipkin_result", ok: true, data: {} });
    expect(JSON.parse(await readFile(modelsPath, "utf8"))).toEqual({
      extra: true,
      providers: {
        local: other,
        "codex-lb": {
          baseUrl: SAVE.baseUrl,
          api: SAVE.api,
          apiKey: "sk-test",
          models: [
            { id: "gpt-a", input: ["text", "image"] },
            { id: "gpt-b", input: ["text", "image"] },
          ],
        },
      },
    });
  });

  it("keeps the stored API key when the new one is empty", async () => {
    await runMode("provider_save", dirs, SAVE);
    expect(await runMode("provider_save", dirs, { ...SAVE, apiKey: "", images: false, models: ["gpt-c"] })).toMatchObject({ ok: true });
    const saved = JSON.parse(await readFile(modelsPath, "utf8")) as { providers: Record<string, unknown> };
    expect(saved.providers["codex-lb"]).toEqual({ baseUrl: SAVE.baseUrl, api: SAVE.api, apiKey: "sk-test", models: [{ id: "gpt-c" }] });
  });

  it("rejects invalid models.json without writing", async () => {
    await writeFile(modelsPath, "{ not json");
    expect(await runMode("provider_save", dirs, SAVE)).toEqual({
      type: "pipkin_result",
      ok: false,
      code: "models_invalid",
      error: "models.json is not valid JSON. Ask the agent to fix it.",
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
    const saved = JSON.parse(await readFile(modelsPath, "utf8")) as { providers: Record<string, unknown> };
    expect(Object.keys(saved.providers)).toEqual(["second"]);
  });
});
