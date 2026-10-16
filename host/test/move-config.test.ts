import { access, mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, makeDirs, removeDirs, runMode, startHost } from "./harness.js";

describe("moving the config folder off shared storage", () => {
  let dirs: Dirs;
  let legacy: string;
  let env: Record<string, string>;

  beforeEach(async () => {
    dirs = await makeDirs();
    // The config folder does not exist until the move creates it.
    await rm(dirs.configDir, { recursive: true });
    legacy = path.join(dirs.base, "sdcard/.pipkin/pi");
    env = { PIPKIN_LEGACY_CONFIG_DIR: legacy };
    await mkdir(path.join(legacy, "sessions/--home--"), { recursive: true });
    await writeFile(path.join(legacy, "settings.json"), '{"theme":"dark"}');
    await writeFile(path.join(legacy, "sessions/--home--/parent.jsonl"), '{"type":"session","cwd":"/home"}\n{"type":"message"}\n');
    await writeFile(
      path.join(legacy, "sessions/--home--/fork.jsonl"),
      `${JSON.stringify({ type: "session", cwd: "/home", parentSession: path.join(legacy, "sessions/--home--/parent.jsonl") })}\n{"type":"message","text":"kept"}\n`,
    );
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  async function hello(): Promise<Record<string, unknown> | undefined> {
    const { run } = await startHost("sessions", dirs, env);
    const message = await run.next();
    run.socket.end();
    await run.exited;
    return message;
  }

  it("moves the folder in two steps, refusing every other mode until both are done", async () => {
    expect(await hello()).toMatchObject({ configMove: true });
    expect(await runMode("sessions", dirs, {}, env)).toMatchObject({ ok: false, code: "config_not_moved" });

    expect(await runMode("move_config", dirs, {}, env)).toEqual({
      type: "pipkin_result",
      ok: true,
      data: {
        from: legacy,
        to: dirs.configDir,
        sessions: expect.arrayContaining(["sessions/--home--/parent.jsonl", "sessions/--home--/fork.jsonl"]),
      },
    });
    expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
    const [header, rest] = (await readFile(path.join(dirs.configDir, "sessions/--home--/fork.jsonl"), "utf8")).split("\n", 2);
    expect(JSON.parse(header ?? "")).toEqual({ type: "session", cwd: "/home", parentSession: path.join(dirs.configDir, "sessions/--home--/parent.jsonl") });
    expect(rest).toBe('{"type":"message","text":"kept"}');
    // Until Pipkin has updated the paths it keeps, the original stays and other modes still wait.
    await access(legacy);
    expect(await runMode("sessions", dirs, {}, env)).toMatchObject({ ok: false, code: "config_not_moved" });

    expect(await runMode("move_config", dirs, { finish: true }, env)).toMatchObject({ ok: true });
    await expect(access(legacy)).rejects.toThrow(/ENOENT/);
    expect(await hello()).toMatchObject({ configMove: false });
  });

  it("reports the copy again without copying twice when the app asks before finishing", async () => {
    await runMode("move_config", dirs, {}, env);
    await writeFile(path.join(dirs.configDir, "settings.json"), '{"changed":"after the copy"}');
    expect(await runMode("move_config", dirs, {}, env)).toMatchObject({ ok: true, data: { from: legacy } });
    expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"changed":"after the copy"}');
  });

  it("keeps a config folder that already exists in Termux under another name", async () => {
    await mkdir(dirs.configDir, { recursive: true });
    await writeFile(path.join(dirs.configDir, "settings.json"), '{"already":"here"}');
    await runMode("move_config", dirs, {}, env);
    const kept = (await readdir(path.dirname(dirs.configDir))).filter((name) => name.startsWith("config.before-move-"));
    expect(kept).toHaveLength(1);
    expect(await readFile(path.join(path.dirname(dirs.configDir), kept[0] ?? "", "settings.json"), "utf8")).toBe('{"already":"here"}');
    expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
  });

  it("discards a copy a crash left half-done and copies again", async () => {
    const leftover = `${dirs.configDir}.moving-123`;
    await mkdir(leftover, { recursive: true });
    await writeFile(path.join(leftover, "settings.json"), "{ half");
    await runMode("move_config", dirs, {}, env);
    await expect(access(leftover)).rejects.toThrow(/ENOENT/);
    expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
  });

  it("refuses to delete the original before it was copied", async () => {
    expect(await runMode("move_config", dirs, { finish: true }, env)).toMatchObject({ ok: false, code: "move_failed" });
    await access(path.join(legacy, "settings.json"));
  });

  it("leaves alone a shared-storage folder that appears after the move finished", async () => {
    await runMode("move_config", dirs, {}, env);
    await runMode("move_config", dirs, { finish: true }, env);
    await mkdir(legacy, { recursive: true });
    await writeFile(path.join(legacy, "settings.json"), '{"from":"an old Pipkin"}');
    expect(await hello()).toMatchObject({ configMove: false });
    expect(await runMode("move_config", dirs, {}, env)).toMatchObject({ ok: true, data: { from: null } });
    await access(path.join(legacy, "settings.json"));
  });

  it("has nothing to move on a phone that never kept the config on shared storage", async () => {
    env = { PIPKIN_LEGACY_CONFIG_DIR: path.join(dirs.base, "missing") };
    expect(await hello()).toMatchObject({ configMove: false });
    expect(await runMode("move_config", dirs, {}, env)).toMatchObject({ ok: true, data: { from: null, sessions: [] } });
    await expect(access(dirs.configDir)).rejects.toThrow(/ENOENT/);
  });
});
