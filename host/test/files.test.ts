import { mkdir, stat, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { JsonObject } from "../src/framing.js";
import { type Dirs, type HostRun, makeDirs, removeDirs, startHost } from "./harness.js";

describe("files mode", () => {
  let dirs: Dirs;
  let workspace: string;
  let outside: string;
  let run: HostRun;
  let nextId = 0;

  beforeEach(async () => {
    dirs = await makeDirs();
    workspace = path.join(dirs.base, "workspace");
    outside = path.join(dirs.base, "outside");
    await mkdir(path.join(workspace, "src"), { recursive: true });
    await mkdir(outside);
    await writeFile(path.join(outside, "secret.txt"), "secret");
    ({ run } = await startHost("files", dirs));
    await run.next();
    run.send({ type: "pipkin_request", params: {} });
  });

  afterEach(async () => {
    run.socket.destroy();
    await removeDirs(dirs);
  });

  /** Sends [type] with a fresh id and the workspace root, and returns the reply carrying that id. */
  async function request(type: string, fields: JsonObject): Promise<JsonObject | undefined> {
    const id = ++nextId;
    run.send({ type, id, root: workspace, ...fields });
    for (;;) {
      const message = await run.next();
      if (message === undefined || message.id === id) return message;
    }
  }

  const list = (target: string) => request("pipkin_files_list", { path: target });
  const read = (target: string) => request("pipkin_files_read", { path: target });
  const names = async (target: string) => ((await list(target))?.entries as { name: string }[] | undefined)?.map((entry) => entry.name);

  it("starts with a ready message", async () => {
    expect(await run.next()).toEqual({ type: "pipkin_files_ready" });
  });

  it("lists folders first, then by name ignoring case and reading numbers, dot entries included", async () => {
    for (const name of ["project10", "Project2", ".config"]) await mkdir(path.join(workspace, name));
    for (const name of ["b.txt", "A.txt", "file10", "file9", ".env"]) await writeFile(path.join(workspace, name), "");
    expect(await names(".")).toEqual([".config", "Project2", "project10", "src", ".env", "A.txt", "b.txt", "file9", "file10"]);
  });

  it("describes each entry with its workspace-relative path", async () => {
    const file = path.join(workspace, "src", "main.ts");
    await writeFile(file, "hello");
    await mkdir(path.join(workspace, "src", "lib"));
    const { mtimeMs } = await stat(file);
    expect(await list("src")).toEqual({
      type: "pipkin_files_listing",
      id: nextId,
      path: "src",
      entries: [
        { name: "lib", path: "src/lib", kind: "directory", size: expect.any(Number), modified: expect.any(Number) },
        { name: "main.ts", path: "src/main.ts", kind: "file", size: 5, modified: Math.floor(mtimeMs) },
      ],
    });
    expect(await list("./src/")).toMatchObject({ path: "src" });
    expect(await list(".")).toMatchObject({ path: ".", entries: [{ name: "src", path: "src" }] });
  });

  it("follows links inside the workspace and skips dangling ones and those leading out", async () => {
    await symlink(path.join(workspace, "src"), path.join(workspace, "src-link"));
    await symlink(path.join(workspace, "gone.md"), path.join(workspace, "dangling.md"));
    await symlink(outside, path.join(workspace, "escape"));
    await symlink(path.join(outside, "secret.txt"), path.join(workspace, "secret.txt"));
    expect(await list(".")).toMatchObject({
      entries: [
        { name: "src", kind: "directory" },
        { name: "src-link", path: "src-link", kind: "directory" },
      ],
    });
  });

  it("reports listing failures in the listing", async () => {
    await writeFile(path.join(workspace, "notes.txt"), "");
    expect(await list("missing")).toMatchObject({ path: "missing", entries: [], error: "This file or folder no longer exists." });
    expect(await list("notes.txt")).toMatchObject({ entries: [], error: "This isn't a folder." });
    expect(await list("../outside")).toMatchObject({ entries: [], error: "That path is outside the workspace." });
    await symlink(outside, path.join(workspace, "escape"));
    expect(await list("escape")).toMatchObject({ entries: [], error: "That path is outside the workspace." });
    const missingRoot = path.join(dirs.base, "nowhere");
    expect(await request("pipkin_files_list", { root: missingRoot, path: "." })).toMatchObject({
      entries: [],
      error: `Folder not found: ${missingRoot}`,
    });
    expect(await request("pipkin_files_list", { root: "relative", path: "." })).toMatchObject({ error: "Folder not found: relative" });
  });

  it("ignores malformed requests", async () => {
    run.send({ type: "pipkin_files_list", id: "1", root: workspace, path: "." });
    run.send({ type: "pipkin_files_list", id: 1000, path: "." });
    run.send({ type: "pipkin_files_mkdir", id: 1001, root: workspace, path: "." });
    run.send({ type: "pipkin_files_list", id: 1002, root: workspace, path: "." });
    const seen: unknown[] = [];
    for (;;) {
      const message = await run.next();
      if (message === undefined || message.id === 1002) break;
      seen.push(message.id);
    }
    expect(seen).toEqual([undefined]);
  });

  it("reads text as UTF-8, including empty files", async () => {
    await writeFile(path.join(workspace, "src", "hello.md"), "héllo 👋\n");
    await writeFile(path.join(workspace, "empty.txt"), "");
    await writeFile(path.join(workspace, "icon.svg"), "<svg/>");
    expect(await read("src/hello.md")).toEqual({
      type: "pipkin_files_file",
      id: nextId,
      path: "src/hello.md",
      size: Buffer.byteLength("héllo 👋\n"),
      kind: "text",
      content: "héllo 👋\n",
    });
    expect(await read("empty.txt")).toMatchObject({ size: 0, kind: "text", content: "" });
    expect(await read("icon.svg")).toMatchObject({ kind: "text", content: "<svg/>" });
  });

  it("reads images as base64", async () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0xff]);
    await writeFile(path.join(workspace, "pic.PNG"), png);
    const reply = await read("pic.PNG");
    expect(reply).toMatchObject({ path: "pic.PNG", size: png.length, kind: "image" });
    expect(Buffer.from(reply?.content as string, "base64")).toEqual(png);
  });

  it("calls files with a NUL or invalid UTF-8 binary, without content", async () => {
    await writeFile(path.join(workspace, "nul.dat"), Buffer.from("abc\0def"));
    await writeFile(path.join(workspace, "latin1.txt"), Buffer.from([0x63, 0x61, 0x66, 0xe9]));
    const nul = await read("nul.dat");
    expect(nul).toMatchObject({ size: 7, kind: "binary" });
    expect(nul).not.toHaveProperty("content");
    expect(await read("latin1.txt")).toMatchObject({ size: 4, kind: "binary" });
  });

  it("calls text over 2 MiB too large, without content", async () => {
    const size = 2 * 1024 * 1024 + 1;
    await writeFile(path.join(workspace, "big.log"), "a".repeat(size));
    const reply = await read("big.log");
    expect(reply).toMatchObject({ path: "big.log", size, kind: "too_large" });
    expect(reply).not.toHaveProperty("content");
  });

  it("refuses folders and paths leading out of the workspace", async () => {
    await symlink(path.join(outside, "secret.txt"), path.join(workspace, "secret.txt"));
    expect(await read("src")).toMatchObject({ path: "src", error: "This is a folder." });
    expect(await read("secret.txt")).toMatchObject({ error: "That path is outside the workspace." });
    expect(await read("../outside/secret.txt")).toMatchObject({ error: "That path is outside the workspace." });
    expect(await read("missing.txt")).toMatchObject({ error: "This file or folder no longer exists." });
  });

  it("makes a folder inside another and names it relative to the workspace", async () => {
    expect(await request("pipkin_files_mkdir", { path: "src", name: " lib " })).toEqual({ type: "pipkin_files_made", id: nextId, path: "src/lib" });
    expect((await stat(path.join(workspace, "src", "lib"))).isDirectory()).toBe(true);
    expect(await request("pipkin_files_mkdir", { path: ".", name: "docs" })).toMatchObject({ path: "docs" });
  });

  it("refuses folder names that are taken or invalid, and parents outside the workspace", async () => {
    await writeFile(path.join(workspace, "taken"), "");
    expect(await request("pipkin_files_mkdir", { path: ".", name: "src" })).toMatchObject({
      error: "A file or folder named src already exists.",
    });
    expect(await request("pipkin_files_mkdir", { path: ".", name: "taken" })).toMatchObject({
      error: "A file or folder named taken already exists.",
    });
    for (const name of ["", "  ", ".", "..", "a/b", "a\0b"]) {
      const reply = await request("pipkin_files_mkdir", { path: ".", name });
      expect(reply).toMatchObject({ error: expect.stringContaining("Folder names") });
      expect(reply).not.toHaveProperty("path");
    }
    expect(await request("pipkin_files_mkdir", { path: "..", name: "sneaky" })).toMatchObject({ error: "That path is outside the workspace." });
    await expect(stat(path.join(dirs.base, "sneaky"))).rejects.toThrow("ENOENT");
  });
});
