import { access, mkdir, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, makeDirs, removeDirs, runMode } from "./harness.js";

describe("delete_sessions mode", () => {
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

  async function session(name: string): Promise<string> {
    const file = path.join(sessions, "--project--", name);
    await writeFile(file, "{}\n");
    return file;
  }

  it("deletes every session file it is given", async () => {
    const a = await session("a.jsonl");
    const b = await session("b.jsonl");
    expect(await runMode("delete_sessions", dirs, { paths: [a, b] })).toEqual({ type: "pipkin_result", ok: true, data: {} });
    await expect(access(a)).rejects.toThrow(/ENOENT/);
    await expect(access(b)).rejects.toThrow(/ENOENT/);
  });

  it("counts a file that is already gone as deleted", async () => {
    const a = await session("a.jsonl");
    const gone = path.join(sessions, "--project--", "gone.jsonl");
    expect(await runMode("delete_sessions", dirs, { paths: [gone, a] })).toMatchObject({ ok: true });
    await expect(access(a)).rejects.toThrow(/ENOENT/);
  });

  it("deletes nothing when any path is outside the sessions folder", async () => {
    const a = await session("a.jsonl");
    const escape = path.join(sessions, "--project--", "..", "..", "settings.json");
    expect(await runMode("delete_sessions", dirs, { paths: [a, escape] })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(a)).resolves.toBeUndefined();
    await expect(access(outside)).resolves.toBeUndefined();
  });

  it("rejects symlinks that point outside the sessions folder", async () => {
    const link = path.join(sessions, "--project--", "link.jsonl");
    await symlink(outside, link);
    expect(await runMode("delete_sessions", dirs, { paths: [link] })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(outside)).resolves.toBeUndefined();
  });

  it("rejects the sessions folder itself", async () => {
    expect(await runMode("delete_sessions", dirs, { paths: [sessions] })).toMatchObject({ ok: false, code: "outside_sessions" });
  });

  it("rejects paths that are not a list of strings", async () => {
    expect(await runMode("delete_sessions", dirs, { paths: "a.jsonl" })).toMatchObject({ ok: false, code: "bad_params" });
  });
});
