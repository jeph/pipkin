import { writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

describe("modes backed by the Pi SDK", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("lists no sessions in a fresh config", async () => {
    expect(await runMode("sessions", dirs, {})).toEqual({ type: "pipkin_result", ok: true, data: { sessions: [] } });
  });

  it("reports subscription providers and custom providers from models.json", async () => {
    const provider = {
      baseUrl: "https://example.test/v1",
      api: "openai-responses",
      apiKey: "k",
      models: [{ id: "m1", input: ["text", "image"] }, { id: "m2" }],
    };
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers: { "codex-lb": provider } }));
    const result = await runMode("auth_status", dirs, {});
    expect(result).toMatchObject({ ok: true });
    const data = result.data as { builtin: { id: string; configured: boolean }[]; custom: unknown[]; modelsError?: string };
    expect(data.builtin.map((entry) => entry.id)).toEqual(["anthropic", "openai-codex"]);
    expect(data.custom).toEqual([
      { id: "codex-lb", baseUrl: provider.baseUrl, api: provider.api, models: ["m1", "m2"], images: true },
    ]);
    expect(data.modelsError).toBeUndefined();
  });

  it("reports an unparseable models.json as modelsError with no custom providers", async () => {
    await writeFile(path.join(dirs.configDir, "models.json"), "{");
    const result = await runMode("auth_status", dirs, {});
    expect(result.data).toMatchObject({ custom: [], modelsError: expect.any(String) });
  });
});
