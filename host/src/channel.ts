/**
 * Pipkin's channel between the Pi extensions it bundles and the app. The host starts Pi with one more socket, on
 * fd [CHANNEL_FD], that carries JSONL both ways: `{"topic":…,"data":…}` per line. Lines from Pi reach the app as
 * `{"type":"pipkin_channel","topic":…,"data":…}`, merged into Pi's RPC output only between two of Pi's lines; lines the
 * app sends Pi with that type go to the socket instead of Pi's stdin. Private to Pipkin: the app and the extensions
 * ship together, so topics need no versioning.
 */
import { isObject, LineSplitter, LineTooLong } from "./framing.js";

export const CHANNEL_FD = 3;
export const CHANNEL_TYPE = "pipkin_channel";

/** The topic of the host's first line on the socket, which tells an extension the far end is Pipkin's host. */
export const HELLO_TOPIC = "pipkin";

/** Channel messages are small state, never transcripts or images. */
export const MAX_CHANNEL_LINE = 1024 * 1024;

const TOPIC = /^[a-z0-9][a-z0-9_-]*$/;

const NEWLINE = 0x0a;

/** What the app's channel lines start with: the app writes `type` first, so the host never parses Pi's lines. */
const APP_PREFIX = Buffer.from(`{"type":"${CHANNEL_TYPE}"`);

export interface ChannelMessage {
  topic: string;
  data: unknown;
}

/**
 * One line as a message, or null when it isn't JSON with a plain topic. Lines from the app also carry [type]; lines on
 * the socket have none.
 */
export function parseChannelLine(line: string, type?: string): ChannelMessage | null {
  let value: unknown;
  try {
    value = JSON.parse(line);
  } catch {
    return null;
  }
  if (!isObject(value) || value.type !== type || typeof value.topic !== "string" || !TOPIC.test(value.topic)) return null;
  return { topic: value.topic, data: value.data ?? null };
}

/** A message as a line on the socket. */
export function encodeChannelMessage({ topic, data }: ChannelMessage): string {
  return `${JSON.stringify({ topic, data })}\n`;
}

/**
 * Splits the socket's bytes into messages. A line over [MAX_CHANNEL_LINE] or one that isn't a message is dropped with
 * [onDropped], and the lines after it still arrive.
 */
export class ChannelReader {
  private splitter: LineSplitter;

  constructor(
    private readonly onMessage: (message: ChannelMessage) => void,
    private readonly onDropped: (reason: string) => void,
  ) {
    this.splitter = this.newSplitter();
  }

  push(chunk: Buffer): void {
    try {
      this.splitter.push(chunk);
    } catch (error) {
      if (!(error instanceof LineTooLong)) throw error;
      this.onDropped(error.message);
      // The rest of the long line arrives as a line of its own, which isn't a message either.
      this.splitter = this.newSplitter();
    }
  }

  private newSplitter(): LineSplitter {
    return new LineSplitter((line) => {
      if (line.length === 0) return;
      const message = parseChannelLine(line);
      if (message === null) this.onDropped("not a channel message");
      else this.onMessage(message);
    }, MAX_CHANNEL_LINE);
  }
}

/**
 * Pi's stdout with channel messages merged in. Pi's output arrives in chunks that can end mid-line, so a message waits
 * for the next line boundary: written at once when the output last ended a line, otherwise after the first newline of
 * the next chunk.
 */
export class OutputMerger {
  private atLineStart = true;
  private waiting: string[] = [];

  constructor(private readonly write: (data: Buffer | string) => void) {}

  output(chunk: Buffer): void {
    if (chunk.length === 0) return;
    if (this.waiting.length > 0 && !this.atLineStart) {
      const newline = chunk.indexOf(NEWLINE);
      if (newline < 0) {
        this.write(chunk);
        return;
      }
      this.write(chunk.subarray(0, newline + 1));
      this.atLineStart = true;
      chunk = chunk.subarray(newline + 1);
    }
    if (this.atLineStart) this.flush();
    if (chunk.length === 0) return;
    this.write(chunk);
    this.atLineStart = chunk[chunk.length - 1] === NEWLINE;
  }

  channel(message: ChannelMessage): void {
    this.waiting.push(`${JSON.stringify({ type: CHANNEL_TYPE, topic: message.topic, data: message.data })}\n`);
    if (this.atLineStart) this.flush();
  }

  /** Once Pi's output ended: what still waits goes out, after a newline ending a line Pi left unfinished. */
  end(): void {
    if (!this.atLineStart) this.write("\n");
    this.atLineStart = true;
    this.flush();
  }

  private flush(): void {
    for (const line of this.waiting) this.write(line);
    this.waiting = [];
  }
}

/**
 * The app's bytes for Pi with channel lines taken out. Everything else streams on as it arrives, holding back at most
 * the first few bytes of a line until they show whether it's a channel line; only channel lines are buffered whole.
 */
export class AppInputRouter {
  private mode: "deciding" | "pi" | "channel" | "dropping" = "deciding";
  /** The start of the current line while deciding, or the whole of it once it's a channel line. */
  private parts: Buffer[] = [];
  private length = 0;

  constructor(
    private readonly toPi: (data: Buffer) => void,
    private readonly onMessage: (message: ChannelMessage) => void,
    private readonly onDropped: (reason: string) => void,
  ) {}

  push(chunk: Buffer): void {
    let start = 0;
    while (start < chunk.length) {
      const newline = chunk.indexOf(NEWLINE, start);
      const lineEnd = newline < 0 ? chunk.length : newline + 1;
      if (this.mode === "deciding") {
        start = this.decide(chunk.subarray(start, Math.min(lineEnd, start + APP_PREFIX.length - this.length)), start);
        continue;
      }
      const piece = chunk.subarray(start, lineEnd);
      if (this.mode === "pi") this.toPi(piece);
      else if (this.mode === "channel") this.collect(piece);
      start = lineEnd;
      if (newline >= 0) this.lineEnded();
    }
  }

  /** Takes [piece], the next bytes of a line's start, and returns where reading goes on. */
  private decide(piece: Buffer, start: number): number {
    const matches = piece.equals(APP_PREFIX.subarray(this.length, this.length + piece.length));
    this.parts.push(piece);
    this.length += piece.length;
    const endsLine = piece[piece.length - 1] === NEWLINE;
    if (!matches) {
      this.toPi(Buffer.concat(this.parts, this.length));
      this.parts = [];
      this.length = 0;
      this.mode = "pi";
    } else if (this.length === APP_PREFIX.length) {
      this.mode = "channel";
    }
    if (endsLine) this.lineEnded();
    return start + piece.length;
  }

  private collect(piece: Buffer): void {
    this.length += piece.length;
    if (this.length > MAX_CHANNEL_LINE) {
      this.onDropped(`Line exceeds ${MAX_CHANNEL_LINE} bytes`);
      this.parts = [];
      this.mode = "dropping";
      return;
    }
    this.parts.push(piece);
  }

  /** Once the app's input ended: the start of a line still held back goes to Pi, as it would have without the channel. */
  end(): void {
    if (this.mode === "deciding" && this.length > 0) this.toPi(Buffer.concat(this.parts, this.length));
    this.parts = [];
    this.length = 0;
  }

  private lineEnded(): void {
    if (this.mode === "channel") {
      const message = parseChannelLine(Buffer.concat(this.parts, this.length).toString("utf8"), CHANNEL_TYPE);
      if (message === null) this.onDropped("not a channel message");
      else this.onMessage(message);
    }
    this.parts = [];
    this.length = 0;
    this.mode = "deciding";
  }
}
