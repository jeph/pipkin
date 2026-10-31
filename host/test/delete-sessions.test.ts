import { access, mkdir, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, makeDirs, removeDirs, runOp } from "./harness.js";

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
    expect(await runOp("delete_sessions", dirs, { paths: [a, b] })).toEqual({ type: "pipkin_result", ok: true, data: {} });
    await expect(access(a)).rejects.toThrow(/ENOENT/);
    await expect(access(b)).rejects.toThrow(/ENOENT/);
  });

  it("counts a file that is already gone as deleted", async () => {
    const a = await session("a.jsonl");
    const gone = path.join(sessions, "--project--", "gone.jsonl");
    expect(await runOp("delete_sessions", dirs, { paths: [gone, a] })).toMatchObject({ ok: true });
    await expect(access(a)).rejects.toThrow(/ENOENT/);
  });

  it("deletes nothing when any path is outside the sessions folder", async () => {
    const a = await session("a.jsonl");
    const escape = path.join(sessions, "--project--", "..", "..", "settings.json");
    expect(await runOp("delete_sessions", dirs, { paths: [a, escape] })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(a)).resolves.toBeUndefined();
    await expect(access(outside)).resolves.toBeUndefined();
  });

  it("rejects symlinks that point outside the sessions folder", async () => {
    const link = path.join(sessions, "--project--", "link.jsonl");
    await symlink(outside, link);
    expect(await runOp("delete_sessions", dirs, { paths: [link] })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(outside)).resolves.toBeUndefined();
  });

  it("rejects the sessions folder itself", async () => {
    expect(await runOp("delete_sessions", dirs, { paths: [sessions] })).toMatchObject({ ok: false, code: "outside_sessions" });
  });

  it("rejects paths that are not a list of strings", async () => {
    expect(await runOp("delete_sessions", dirs, { paths: "a.jsonl" })).toMatchObject({ ok: false, code: "bad_params" });
  });

  /** A session file with header id [id] and a subagent transcript in its `subagents/<id>/` folder. */
  async function sessionWithSubagents(name: string, id: string): Promise<{ file: string; folder: string }> {
    const file = path.join(sessions, "--project--", name);
    await writeFile(file, `${JSON.stringify({ type: "session", version: 3, id, timestamp: "2026-10-12T00:00:00.000Z", cwd: "/" })}\n`);
    const folder = path.join(sessions, "--project--", "subagents", id);
    await mkdir(folder, { recursive: true });
    await writeFile(path.join(folder, "child.jsonl"), "{}\n");
    return { file, folder };
  }

  it("deletes each session's subagent transcripts with it", async () => {
    const deleted = await sessionWithSubagents("a.jsonl", "aaa");
    const kept = await sessionWithSubagents("b.jsonl", "bbb");
    expect(await runOp("delete_sessions", dirs, { paths: [deleted.file] })).toMatchObject({ ok: true });
    await expect(access(deleted.file)).rejects.toThrow(/ENOENT/);
    await expect(access(deleted.folder)).rejects.toThrow(/ENOENT/);
    await expect(access(kept.folder)).resolves.toBeUndefined();
  });

  it("deletes a session that has no subagents", async () => {
    const file = path.join(sessions, "--project--", "a.jsonl");
    await writeFile(file, `${JSON.stringify({ type: "session", version: 3, id: "aaa", timestamp: "", cwd: "/" })}\n`);
    expect(await runOp("delete_sessions", dirs, { paths: [file] })).toMatchObject({ ok: true });
    await expect(access(file)).rejects.toThrow(/ENOENT/);
  });

  it("deletes no folder a session's id would put outside its subagents folder", async () => {
    const file = path.join(sessions, "--project--", "a.jsonl");
    await writeFile(file, `${JSON.stringify({ type: "session", version: 3, id: "../..", timestamp: "", cwd: "/" })}\n`);
    expect(await runOp("delete_sessions", dirs, { paths: [file] })).toMatchObject({ ok: true });
    await expect(access(path.join(sessions, "--project--"))).resolves.toBeUndefined();
  });

  it("checks every path before deleting any subagents", async () => {
    const { file, folder } = await sessionWithSubagents("a.jsonl", "aaa");
    const escape = path.join(sessions, "--project--", "..", "..", "settings.json");
    expect(await runOp("delete_sessions", dirs, { paths: [file, escape] })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(folder)).resolves.toBeUndefined();
  });

  it("deletes nothing when a subagents folder links outside the sessions folder", async () => {
    const outsideFolder = path.join(dirs.configDir, "elsewhere");
    const sentinel = path.join(outsideFolder, "aaa", "keep.txt");
    await mkdir(path.dirname(sentinel), { recursive: true });
    await writeFile(sentinel, "keep");
    await symlink(outsideFolder, path.join(sessions, "--project--", "subagents"));
    const file = path.join(sessions, "--project--", "a.jsonl");
    await writeFile(file, `${JSON.stringify({ type: "session", version: 3, id: "aaa", timestamp: "", cwd: "/" })}\n`);
    expect(await runOp("delete_sessions", dirs, { paths: [file] })).toMatchObject({ ok: false, code: "outside_sessions" });
    await expect(access(sentinel)).resolves.toBeUndefined();
    await expect(access(file)).resolves.toBeUndefined();
  });
});
