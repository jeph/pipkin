import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { isObject, type JsonObject } from "../src/framing.js";
import { type Dirs, installRealPi, makeDirs, PACKAGE_JSON, PI_VERSION, removeDirs, runOp, startHost } from "./harness.js";

const PROVIDER = {
  baseUrl: "https://example.test/v1",
  api: "openai-responses",
  apiKey: "k",
  models: [
    { id: "m1", reasoning: true },
    { id: "m2", reasoning: true },
  ],
};

function modelRef(state: JsonObject): string {
  return isObject(state.model) ? `${String(state.model.provider)}/${String(state.model.id)}` : "none";
}

describe("default_model mode", () => {
  let dirs: Dirs;
  let settingsPath: string;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    settingsPath = path.join(dirs.configDir, "settings.json");
    await writeFile(path.join(dirs.configDir, "models.json"), JSON.stringify({ providers: { lb: PROVIDER } }));
    await writeFile(settingsPath, JSON.stringify({ enabledModels: ["lb/m1", "lb/m2"] }));
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  /** Starts a new Pi session through rpc mode and returns its `get_state` data. */
  async function newSessionState(): Promise<JsonObject> {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, pipkinExtensions: [] } });
    run.send({ id: "s", type: "get_state" });
    for (;;) {
      const message = await run.next();
      if (message === undefined) throw new Error("rpc closed before get_state answered");
      if (message.type === "response" && message.id === "s" && isObject(message.data)) {
        run.socket.end();
        await run.exited;
        return message.data;
      }
    }
  }

  it("makes a new session start with the saved model and thinking level, keeping other settings", async () => {
    expect(await runOp("default_model", dirs, { provider: "lb", modelId: "m2", thinkingLevel: null })).toMatchObject({ ok: true });
    expect(await runOp("default_model", dirs, { provider: null, modelId: null, thinkingLevel: "high" })).toMatchObject({ ok: true });

    const state = await newSessionState();
    expect(modelRef(state)).toBe("lb/m2");
    expect(state.thinkingLevel).toBe("high");
    expect(JSON.parse(await readFile(settingsPath, "utf8"))).toMatchObject({
      enabledModels: ["lb/m1", "lb/m2"],
      defaultProvider: "lb",
      defaultModel: "m2",
      defaultThinkingLevel: "high",
    });
  }, 30_000);

  it("falls back to Pi's usual choice when the saved model is out of scope", async () => {
    await runOp("default_model", dirs, { provider: "lb", modelId: "m2", thinkingLevel: "high" });
    await writeFile(settingsPath, JSON.stringify({ ...JSON.parse(await readFile(settingsPath, "utf8")), enabledModels: ["lb/m1"] }));

    expect(modelRef(await newSessionState())).toBe("lb/m1");
  }, 30_000);

  it("rejects an unknown thinking level without writing", async () => {
    const before = await readFile(settingsPath, "utf8");
    expect(await runOp("default_model", dirs, { provider: null, modelId: null, thinkingLevel: "ultra" })).toMatchObject({
      ok: false,
      code: "bad_params",
    });
    expect(await readFile(settingsPath, "utf8")).toBe(before);
  });
});
