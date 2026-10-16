import { describe, expect, it } from "vitest";
import { readTar, TarError, tarStream, type TarEntry } from "../src/tar.js";

async function* chunks(...parts: string[]): AsyncGenerator<Buffer> {
  for (const part of parts) yield Buffer.from(part);
}

function file(path: string, content: string, mode = 0o644): TarEntry {
  return { type: "file", path, mode, mtime: 1_700_000_000, size: Buffer.byteLength(content), content: chunks(content) };
}

async function* list(entries: TarEntry[]): AsyncGenerator<TarEntry> {
  yield* entries;
}

async function archive(entries: TarEntry[]): Promise<Buffer> {
  const parts: Buffer[] = [];
  for await (const part of tarStream(list(entries))) parts.push(part);
  return Buffer.concat(parts);
}

/** Reads `bytes` back as plain objects, file contents as strings; `chunkSize` splits the input like a stream would. */
async function read(bytes: Buffer, chunkSize = 7): Promise<Record<string, unknown>[]> {
  async function* source(): AsyncGenerator<Buffer> {
    for (let offset = 0; offset < bytes.length; offset += chunkSize) yield bytes.subarray(offset, offset + chunkSize);
  }
  const entries: Record<string, unknown>[] = [];
  for await (const entry of readTar(source())) {
    if (entry.type !== "file") {
      entries.push({ ...entry });
      continue;
    }
    const parts: Buffer[] = [];
    for await (const part of entry.content) parts.push(part);
    entries.push({ type: "file", path: entry.path, mode: entry.mode, mtime: entry.mtime, size: entry.size, text: Buffer.concat(parts).toString("utf8") });
  }
  return entries;
}

describe("tar", () => {
  it("round-trips files, folders and symlinks, including long and non-ASCII paths", async () => {
    const longPath = `config/npm/node_modules/${"deeply-nested-package/".repeat(8)}index.js`;
    const longTarget = `../${"x".repeat(150)}/bin.js`;
    const bytes = await archive([
      { type: "directory", path: "config/extensions", mode: 0o755, mtime: 1_700_000_001 },
      file("config/auth.json", '{"secret":true}', 0o600),
      file("config/empty.txt", ""),
      file(longPath, "module.exports = 1;\n", 0o755),
      file("config/skills/café/SKILL.md", "# ünïcödé\n"),
      { type: "symlink", path: "config/npm/node_modules/.bin/tool", target: longTarget, mtime: 1_700_000_002 },
      { type: "symlink", path: "config/short-link", target: "auth.json", mtime: 1_700_000_003 },
    ]);
    expect(bytes.length % 512).toBe(0);
    expect(await read(bytes)).toEqual([
      { type: "directory", path: "config/extensions", mode: 0o755, mtime: 1_700_000_001 },
      { type: "file", path: "config/auth.json", mode: 0o600, mtime: 1_700_000_000, size: 15, text: '{"secret":true}' },
      { type: "file", path: "config/empty.txt", mode: 0o644, mtime: 1_700_000_000, size: 0, text: "" },
      { type: "file", path: longPath, mode: 0o755, mtime: 1_700_000_000, size: 20, text: "module.exports = 1;\n" },
      { type: "file", path: "config/skills/café/SKILL.md", mode: 0o644, mtime: 1_700_000_000, size: 14, text: "# ünïcödé\n" },
      { type: "symlink", path: "config/npm/node_modules/.bin/tool", target: longTarget, mtime: 1_700_000_002 },
      { type: "symlink", path: "config/short-link", target: "auth.json", mtime: 1_700_000_003 },
    ]);
  });

  it("keeps an archive readable when a file shrinks or grows after it was listed", async () => {
    const shrunk: TarEntry = { type: "file", path: "shrunk", mode: 0o644, mtime: 0, size: 10, content: chunks("abc") };
    const grown: TarEntry = { type: "file", path: "grown", mode: 0o644, mtime: 0, size: 3, content: chunks("abcdef", "ghi") };
    const entries = await read(await archive([shrunk, grown, file("after", "still here")]));
    expect(entries.map((entry) => [entry.path, entry.text])).toEqual([
      ["shrunk", "abc\0\0\0\0\0\0\0"],
      ["grown", "abc"],
      ["after", "still here"],
    ]);
  });

  it("skips a file's content the reader leaves unread", async () => {
    const bytes = await archive([file("a", "x".repeat(2000)), file("b", "second")]);
    const paths: string[] = [];
    async function* source(): AsyncGenerator<Buffer> {
      yield bytes;
    }
    let second = "";
    for await (const entry of readTar(source())) {
      paths.push(entry.path);
      if (entry.type === "file" && entry.path === "b") for await (const part of entry.content) second += part.toString();
    }
    expect(paths).toEqual(["a", "b"]);
    expect(second).toBe("second");
  });

  it("rejects a header whose checksum does not match", async () => {
    const bytes = await archive([file("a", "content")]);
    bytes[10] = 0x41;
    await expect(read(bytes)).rejects.toThrow(TarError);
  });

  it("rejects an archive cut off in the middle of a file or before its end marker", async () => {
    const bytes = await archive([file("a", "x".repeat(1000))]);
    await expect(read(bytes.subarray(0, 700))).rejects.toThrow(/ends in the middle/);
    await expect(read(bytes.subarray(0, 1536))).rejects.toThrow(/ends in the middle/);
  });

  it("rejects data after the end marker", async () => {
    const bytes = await archive([file("a", "content")]);
    await expect(read(Buffer.concat([bytes, Buffer.from("trailing")]))).rejects.toThrow(/after its end/);
  });

  it("rejects entry kinds it does not restore, such as hard links", async () => {
    const bytes = await archive([{ type: "symlink", path: "link", target: "a", mtime: 0 }]);
    bytes[156] = "1".charCodeAt(0);
    let sum = 0;
    bytes.write(" ".repeat(8), 148, 8, "ascii");
    for (let index = 0; index < 512; index++) sum += bytes[index] as number;
    bytes.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148, 8, "ascii");
    await expect(read(bytes)).rejects.toThrow(/unsupported entry: link/);
  });

  it("rejects a file that is not a tar archive", async () => {
    await expect(read(Buffer.alloc(1024, 0x61))).rejects.toThrow(TarError);
  });
});
