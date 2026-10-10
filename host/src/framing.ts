/**
 * JSONL framing shared with the app and Pi: LF is the only delimiter and a trailing CR is
 * stripped. Node's readline is never used because it also splits on U+2028 and U+2029,
 * which are legal inside JSON strings.
 */

/** Images arrive base64-encoded inside `prompt`, so lines are allowed to be large. */
export const MAX_LINE = 16 * 1024 * 1024;

export class LineTooLong extends Error {
  constructor(limit: number) {
    super(`Line exceeds ${limit} bytes`);
  }
}

export type JsonObject = Record<string, unknown>;

export function isObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/**
 * Splits a byte stream into lines without quadratic concatenation. A line callback may call
 * `stop()`; the bytes after that line are then kept unparsed in `leftover` so the caller can
 * switch the stream to a raw byte pipe.
 */
export class LineSplitter {
  private parts: Buffer[] = [];
  private size = 0;
  private stopped = false;
  private rest: Buffer = Buffer.alloc(0);

  constructor(
    private readonly onLine: (line: string) => void,
    private readonly limit = MAX_LINE,
  ) {}

  get leftover(): Buffer {
    return this.rest;
  }

  stop(): void {
    this.stopped = true;
  }

  push(chunk: Buffer): void {
    if (this.stopped) {
      this.rest = Buffer.concat([this.rest, chunk]);
      return;
    }
    let start = 0;
    while (start < chunk.length) {
      const end = chunk.indexOf(0x0a, start);
      const piece = chunk.subarray(start, end < 0 ? chunk.length : end);
      this.size += piece.length;
      if (this.size > this.limit) throw new LineTooLong(this.limit);
      if (piece.length > 0) this.parts.push(piece);
      if (end < 0) return;
      const bytes = this.parts.length === 1 ? this.parts[0] : Buffer.concat(this.parts, this.size);
      this.parts = [];
      this.size = 0;
      start = end + 1;
      const line = bytes === undefined ? "" : bytes.toString("utf8");
      this.onLine(line.endsWith("\r") ? line.slice(0, -1) : line);
      if (this.stopped) {
        this.rest = Buffer.from(chunk.subarray(start));
        return;
      }
    }
  }
}

export function encodeLine(message: JsonObject): string {
  return `${JSON.stringify(message)}\n`;
}
