/** Pi subprocess stdout remains newline-delimited JSON; client transport is WSS/JSON-RPC. */

export const WIRE = 16 * 1024 * 1024;

/** A known refusal before mutation dispatch; post-dispatch failures must use Error. */
export class Refusal extends Error {}

export function need(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Refusal(message);
}

export type JsonObject = Record<string, unknown>;

export function isObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function isCount(value: unknown, max = Number.MAX_SAFE_INTEGER): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= max;
}

const decoder = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });

export function parseJson(bytes: Buffer): unknown {
  return JSON.parse(decoder.decode(bytes)) as unknown;
}

/**
 * Accumulates Pi stdout fragments once, not by quadratic concatenation. Never discards
 * an oversized or malformed record: the owner decides what a violation means for Pi.
 */
export class Frames {
  private parts: Buffer[] = [];
  private bytes = 0;

  constructor(
    private readonly deliver: (record: JsonObject, bytes: number) => void,
    private readonly limit = WIRE,
  ) {}

  push(chunk: Buffer): void {
    let start = 0;
    for (;;) {
      const end = chunk.indexOf(10, start);
      const piece = chunk.subarray(start, end < 0 ? chunk.length : end);
      this.bytes += piece.length;
      need(this.bytes <= this.limit, "Wire record exceeds limit");
      if (piece.length > 0) this.parts.push(piece);
      if (end < 0) return;
      const bytes = this.parts.length === 1 ? this.parts[0] : Buffer.concat(this.parts, this.bytes);
      this.parts = [];
      this.bytes = 0;
      need(bytes !== undefined && bytes.length > 0, "Empty wire record");
      const value = parseJson(bytes);
      need(isObject(value), "Wire record must be an object");
      this.deliver(value, bytes.length);
      start = end + 1;
      if (start === chunk.length) return;
    }
  }

  finish(): void {
    need(this.bytes === 0, "Truncated wire record");
  }
}
