/**
 * The `pipkin-helper/1` wire protocol between Pipkin and the helper daemon in Termux: an HMAC challenge-response
 * handshake on the pairing key, then AES-256-GCM frames with per-direction counters, so a relay on the port sees only
 * ciphertext and can't inject, reorder or replay anything. Mirrored by the app's `HelperProtocol`; both assert the
 * spec's test vectors.
 */
import { createCipheriv, createDecipheriv, createHmac, timingSafeEqual } from "node:crypto";
import { isObject, type JsonObject } from "../framing.js";

export const PROTOCOL_VERSION = 0x01;
export const SERVER_MAGIC = Buffer.from("PKH1", "ascii");
export const NONCE_BYTES = 32;
export const MAC_BYTES = 32;
export const CLIENT_HELLO_BYTES = 1 + NONCE_BYTES;
export const SERVER_HELLO_BYTES = SERVER_MAGIC.length + NONCE_BYTES + MAC_BYTES;
const TAG_BYTES = 16;
export const MAX_FRAME = 4 * 1024 * 1024 + TAG_BYTES;

function hmac(key: Buffer, label: string, nonceC: Buffer, nonceS: Buffer): Buffer {
  return createHmac("sha256", key).update(label, "ascii").update(nonceC).update(nonceS).digest();
}

export function serverMac(key: Buffer, nonceC: Buffer, nonceS: Buffer): Buffer {
  return hmac(key, "pipkin-helper-server", nonceC, nonceS);
}

export function clientMac(key: Buffer, nonceC: Buffer, nonceS: Buffer): Buffer {
  return hmac(key, "pipkin-helper-client", nonceC, nonceS);
}

export interface SessionKeys {
  c2s: Buffer;
  s2c: Buffer;
}

export function sessionKeys(key: Buffer, nonceC: Buffer, nonceS: Buffer): SessionKeys {
  return { c2s: hmac(key, "pipkin-helper-c2s", nonceC, nonceS), s2c: hmac(key, "pipkin-helper-s2c", nonceC, nonceS) };
}

export function macEquals(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && timingSafeEqual(a, b);
}

/** Parses the `key` file format (64 hex chars, optional trailing newline) into the 32-byte key. */
export function parseKeyHex(text: string): Buffer | undefined {
  const hex = text.trim();
  return /^[0-9a-fA-F]{64}$/.test(hex) ? Buffer.from(hex, "hex") : undefined;
}

function counterIv(counter: bigint): Buffer {
  const iv = Buffer.alloc(12);
  iv.writeBigUInt64BE(counter, 4);
  return iv;
}

/** Encrypts outgoing frames for one direction; the counter makes every frame's IV unique. */
export class FrameSealer {
  private counter = 0n;

  constructor(private readonly key: Buffer) {}

  /** Returns the ciphertext with its tag, without the length prefix. */
  sealBody(message: JsonObject): Buffer {
    const cipher = createCipheriv("aes-256-gcm", this.key, counterIv(this.counter++));
    const body = Buffer.concat([cipher.update(JSON.stringify(message), "utf8"), cipher.final(), cipher.getAuthTag()]);
    if (body.length > MAX_FRAME) throw new FrameError("Frame too large");
    return body;
  }

  seal(message: JsonObject): Buffer {
    const body = this.sealBody(message);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(body.length);
    return Buffer.concat([length, body]);
  }
}

export class FrameError extends Error {}

/**
 * Splits and decrypts incoming frames for one direction. Any bad length, failed tag (tampering, replay, reordering)
 * or non-object plaintext throws a {@link FrameError}; the caller must then close the connection.
 */
export class FrameOpener {
  private counter = 0n;
  private chunks: Buffer[] = [];
  private buffered = 0;
  private failed = false;

  constructor(private readonly key: Buffer) {}

  push(chunk: Buffer): JsonObject[] {
    if (this.failed) throw new FrameError("Connection already failed");
    this.chunks.push(chunk);
    this.buffered += chunk.length;
    const messages: JsonObject[] = [];
    try {
      for (let message = this.next(); message !== undefined; message = this.next()) messages.push(message);
    } catch (error) {
      this.failed = true;
      throw error instanceof FrameError ? error : new FrameError("Bad frame");
    }
    return messages;
  }

  private next(): JsonObject | undefined {
    if (this.buffered < 4) return undefined;
    const data = this.chunks.length === 1 ? (this.chunks[0] as Buffer) : Buffer.concat(this.chunks, this.buffered);
    this.chunks = [data];
    const length = data.readUInt32BE(0);
    if (length < 1 || length > MAX_FRAME) throw new FrameError(`Bad frame length ${length}`);
    if (data.length < 4 + length) return undefined;
    const body = data.subarray(4, 4 + length);
    const rest = data.subarray(4 + length);
    this.chunks = rest.length > 0 ? [rest] : [];
    this.buffered = rest.length;
    return this.openBody(body);
  }

  /** Decrypts one frame body (ciphertext with its tag, without the length prefix). */
  openBody(body: Buffer): JsonObject {
    if (body.length < TAG_BYTES) throw new FrameError("Frame shorter than its tag");
    const decipher = createDecipheriv("aes-256-gcm", this.key, counterIv(this.counter++));
    decipher.setAuthTag(body.subarray(body.length - TAG_BYTES));
    let text: string;
    try {
      text = Buffer.concat([decipher.update(body.subarray(0, body.length - TAG_BYTES)), decipher.final()]).toString(
        "utf8",
      );
    } catch {
      throw new FrameError("Frame failed authentication");
    }
    const message: unknown = JSON.parse(text);
    if (!isObject(message)) throw new FrameError("Frame isn't a JSON object");
    return message;
  }
}

/** Collects stream bytes until a fixed-size handshake message is complete. */
export class ByteQueue {
  private data: Buffer = Buffer.alloc(0);

  push(chunk: Buffer): void {
    this.data = this.data.length === 0 ? chunk : Buffer.concat([this.data, chunk]);
  }

  peek(count: number): Buffer | undefined {
    return this.data.length < count ? undefined : this.data.subarray(0, count);
  }

  take(count: number): Buffer | undefined {
    if (this.data.length < count) return undefined;
    const head = this.data.subarray(0, count);
    this.data = this.data.subarray(count);
    return head;
  }

  rest(): Buffer {
    const rest = this.data;
    this.data = Buffer.alloc(0);
    return rest;
  }
}
