import { createJSONRPCNotification } from "json-rpc-2.0";
import type { JsonObject } from "./wire.js";

export interface JournalEntry {
  readonly seq: number;
  /** One serialized JSON-RPC event notification, without a newline. */
  readonly message: string;
  readonly bytes: number;
}

/**
 * Per-generation catch-up ring. Sequence numbers start at 1 and never reset; eviction is
 * by record count or serialized bytes, oldest first. A reattaching client learns via
 * `oldestSeq` whether records it never saw were evicted.
 */
export class Journal {
  private entries: JournalEntry[] = [];
  private bytes = 0;
  private last = 0;

  constructor(
    private readonly maxRecords = 2048,
    private readonly maxBytes = 4 * 1024 * 1024,
  ) {}

  get lastSeq(): number {
    return this.last;
  }

  /** Sequence of the oldest retained record, or `lastSeq + 1` when nothing is retained. */
  get oldestSeq(): number {
    return this.entries[0]?.seq ?? this.last + 1;
  }

  append(fields: JsonObject): JournalEntry {
    const seq = ++this.last;
    const message = JSON.stringify(createJSONRPCNotification("agent.event", { ...fields, seq }));
    const entry = { seq, message, bytes: Buffer.byteLength(message) };
    this.entries.push(entry);
    this.bytes += entry.bytes;
    while (this.entries.length > this.maxRecords || this.bytes > this.maxBytes) {
      const evicted = this.entries.shift();
      if (evicted === undefined) break;
      this.bytes -= evicted.bytes;
    }
    return entry;
  }

  after(seq: number): JournalEntry[] {
    let index = 0;
    while (index < this.entries.length && (this.entries[index]?.seq ?? Infinity) <= seq) index++;
    return this.entries.slice(index);
  }
}
