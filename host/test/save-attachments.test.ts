import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { isObject } from "../src/framing.js";
import { type Dirs, makeDirs, removeDirs, runOp } from "./harness.js";

const b64 = (text: string): string => Buffer.from(text).toString("base64");

describe("save_attachments mode", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  async function save(files: unknown): Promise<string[]> {
    const result = await runOp("save_attachments", dirs, { files });
    expect(result).toMatchObject({ ok: true });
    const paths = isObject(result.data) ? result.data.paths : undefined;
    if (!Array.isArray(paths) || !paths.every((p): p is string => typeof p === "string")) throw new Error("no paths");
    return paths;
  }

  it("writes the bytes into one new folder under attachments", async () => {
    const paths = await save([
      { name: "notes.txt", data: b64("hello") },
      { name: "data.csv", data: b64("a,b") },
    ]);
    expect(paths.map((p) => path.basename(p))).toEqual(["notes.txt", "data.csv"]);
    const folders = new Set(paths.map((p) => path.dirname(p)));
    expect([...folders].map((dir) => path.dirname(dir))).toEqual([path.join(dirs.root, "attachments")]);
    expect(await Promise.all(paths.map((p) => readFile(p, "utf8")))).toEqual(["hello", "a,b"]);
  });

  it("keeps names that would leave the folder or hide the file inside it", async () => {
    const paths = await save([
      { name: "../../escape.txt", data: b64("x") },
      { name: "..\\win.txt", data: b64("x") },
      { name: ".bashrc", data: b64("x") },
      { name: "..", data: b64("x") },
    ]);
    expect(paths.map((p) => path.basename(p))).toEqual(["escape.txt", "win.txt", "bashrc", "file"]);
    const folders = new Set(paths.map((p) => path.dirname(p)));
    const listings = await Promise.all([...folders].map(async (dir) => (await readdir(dir)).toSorted()));
    expect(listings).toEqual([["bashrc", "escape.txt", "file", "win.txt"]]);
  });

  it("renames duplicate names instead of overwriting", async () => {
    const paths = await save([
      { name: "a.txt", data: b64("1") },
      { name: "a.txt", data: b64("2") },
      { name: "a.txt", data: b64("3") },
    ]);
    expect(paths.map((p) => path.basename(p))).toEqual(["a.txt", "a-2.txt", "a-3.txt"]);
    expect(await Promise.all(paths.map((p) => readFile(p, "utf8")))).toEqual(["1", "2", "3"]);
  });

  it("rejects an empty list and non-base64 data", async () => {
    expect(await runOp("save_attachments", dirs, { files: [] })).toMatchObject({ ok: false, code: "bad_params" });
    expect(await runOp("save_attachments", dirs, { files: [{ name: "a", data: "not base64!" }] })).toMatchObject({
      ok: false,
      code: "bad_params",
    });
  });
});
