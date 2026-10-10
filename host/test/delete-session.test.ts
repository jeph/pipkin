import { access, mkdir, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, makeDirs, removeDirs, runMode } from "./harness.js";

describe("delete_session mode", () => {
  let dirs: Dirs;
  let sessions: string;
  let outside: string;

  beforeEach(async () => {
    dirs = await makeDirs();
    sessions = path.join(dirs.configDir, "sessions");
    await mkdir(path.join(sessions, "--project--"), { recursive: true });
    outside = path.join(dirs.configDir, "settings.json");
    await writeFile(outside, "{}");
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("deletes a session file inside the sessions folder", async () => {
    const session = path.join(sessions, "--project--", "a.jsonl");
    await writeFile(session, "{}\n");
    expect(await runMode("delete_session", dirs, { path: session })).toEqual({ type: "pipkin_result", ok: true, data: {} });
    await expect(access(session)).rejects.toThrow(/ENOENT/);
  });

  it("rejects ../ escapes", async () => {
    const escape = path.join(sessions, "--project--", "..", "..", "settings.json");
    expect(await runMode("delete_session", dirs, { path: escape })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(outside)).resolves.toBeUndefined();
  });

  it("rejects symlinks that point outside the sessions folder", async () => {
    const link = path.join(sessions, "--project--", "link.jsonl");
    await symlink(outside, link);
    expect(await runMode("delete_session", dirs, { path: link })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(outside)).resolves.toBeUndefined();
  });

  it("rejects the sessions folder itself", async () => {
    expect(await runMode("delete_session", dirs, { path: sessions })).toMatchObject({ ok: false, code: "outside_sessions" });
  });
});
