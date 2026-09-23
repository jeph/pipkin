import { describe, expect, it } from "vitest";
import { Journal } from "../src/journal.js";
import { Frames, Refusal, type JsonObject } from "../src/wire.js";

describe("Frames (Pi stdout JSONL)", () => {
  it("reassembles Pi records split across chunks and delivers several per chunk", () => {
    const seen: JsonObject[] = [];
    const frames = new Frames((record) => seen.push(record));
    frames.push(Buffer.from('{"a":1}\n{"b":'));
    expect(seen).toEqual([{ a: 1 }]);
    frames.push(Buffer.from('2}\n{"c":3}\n'));
    expect(seen).toEqual([{ a: 1 }, { b: 2 }, { c: 3 }]);
    frames.finish();
  });

  it("refuses oversized partial records before they complete", () => {
    const frames = new Frames(() => undefined, 8);
    frames.push(Buffer.from('{"a":12'));
    expect(() => { frames.push(Buffer.from('345678}\n')); }).toThrow(Refusal);
  });

  it("refuses empty lines, non-objects and truncated tails", () => {
    const frames = new Frames(() => undefined);
    expect(() => { frames.push(Buffer.from("\n")); }).toThrow(Refusal);
    expect(() => { new Frames(() => undefined).push(Buffer.from("[1]\n")); }).toThrow(Refusal);
    const partial = new Frames(() => undefined);
    partial.push(Buffer.from('{"a":'));
    expect(() => { partial.finish(); }).toThrow(Refusal);
  });
});

describe("Journal", () => {
  it("numbers from 1 and reports the retained window after eviction", () => {
    const journal = new Journal(3);
    for (let index = 0; index < 5; index++) journal.append({ event: "pi", index });
    expect(journal.lastSeq).toBe(5);
    expect(journal.oldestSeq).toBe(3);
    expect(journal.after(0).map((entry) => entry.seq)).toEqual([3, 4, 5]);
    expect(journal.after(4).map((entry) => entry.seq)).toEqual([5]);
    expect(journal.after(5)).toEqual([]);
  });

  it("bounds retained notifications by UTF-8 bytes and evicts whole entries oldest first", () => {
    const fields = { event: "stderr", text: "界𝄞".repeat(50) };
    const message = JSON.stringify({ jsonrpc: "2.0", method: "agent.event", params: { ...fields, seq: 1 } });
    // Two equal-sized notifications exceed the budget by one UTF-8 byte.
    const limit = Buffer.byteLength(message, "utf8") * 2 - 1;
    const journal = new Journal(100, limit);
    const first = journal.append(fields);
    expect(journal.after(0)).toEqual([first]);
    const second = journal.append(fields);
    expect(first.bytes + second.bytes).toBe(limit + 1);
    expect(journal.oldestSeq).toBe(2);
    expect(journal.after(0)).toEqual([second]);

    const oversized = journal.append({ event: "stderr", text: fields.text.repeat(3) });
    expect(oversized.bytes).toBeGreaterThan(limit);
    expect(journal.after(0)).toEqual([]);
    expect(journal.lastSeq).toBe(3);
    expect(journal.oldestSeq).toBe(4);
  });

  it("emits JSON-RPC event notifications with sequence and UTF-8 byte accounting", () => {
    const journal = new Journal();
    const entry = journal.append({ event: "stderr", generation: 7, text: "Pi: 界𝄞\n" });
    expect(entry.seq).toBe(1);
    expect(JSON.parse(entry.message)).toEqual({
      jsonrpc: "2.0",
      method: "agent.event",
      params: { event: "stderr", generation: 7, text: "Pi: 界𝄞\n", seq: 1 },
    });
    expect(entry.bytes).toBe(Buffer.byteLength(entry.message, "utf8"));
  });
});
