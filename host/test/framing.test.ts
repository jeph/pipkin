import { describe, expect, it } from "vitest";
import { LineSplitter, LineTooLong } from "../src/framing.js";

function collect(limit?: number): { lines: string[]; splitter: LineSplitter } {
  const lines: string[] = [];
  return { lines, splitter: new LineSplitter((line) => lines.push(line), limit) };
}

describe("LineSplitter", () => {
  it("keeps U+2028 and U+2029 inside JSON strings", () => {
    const { lines, splitter } = collect();
    const record = JSON.stringify({ text: "a\u2028b\u2029c" });
    splitter.push(Buffer.from(`${record}\n`));
    expect(lines).toEqual([record]);
    expect(JSON.parse(lines[0] ?? "")).toEqual({ text: "a\u2028b\u2029c" });
  });

  it("reassembles lines split across chunks, including inside a multibyte character", () => {
    const { lines, splitter } = collect();
    const bytes = Buffer.from('{"a":"界"}\n{"b":2}\n');
    splitter.push(bytes.subarray(0, 7));
    splitter.push(bytes.subarray(7, 13));
    expect(lines).toEqual(['{"a":"界"}']);
    splitter.push(bytes.subarray(13));
    expect(lines).toEqual(['{"a":"界"}', '{"b":2}']);
  });

  it("strips a trailing CR", () => {
    const { lines, splitter } = collect();
    splitter.push(Buffer.from('{"a":1}\r\n'));
    expect(lines).toEqual(['{"a":1}']);
  });

  it("rejects a line longer than the limit before it completes", () => {
    const { splitter } = collect(8);
    splitter.push(Buffer.from('{"a":12'));
    expect(() => {
      splitter.push(Buffer.from("345678"));
    }).toThrow(LineTooLong);
  });

  it("keeps the bytes after a stopping line unparsed", () => {
    const lines: string[] = [];
    const splitter: LineSplitter = new LineSplitter((line) => {
      lines.push(line);
      splitter.stop();
    });
    splitter.push(Buffer.from('{"first":1}\n{"raw":'));
    splitter.push(Buffer.from("2}\n"));
    expect(lines).toEqual(['{"first":1}']);
    expect(splitter.leftover.toString()).toBe('{"raw":2}\n');
  });
});
