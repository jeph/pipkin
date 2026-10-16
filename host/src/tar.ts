/**
 * The POSIX tar (pax) format, written and read without a dependency: Node has gzip built in but no tar, and a tar
 * package would nearly double the host script, which travels to Termux as a command-line argument. Only what config
 * backups need is supported: files, folders and symlinks, with paths and link targets of any length in pax headers.
 */

const BLOCK = 512;
/** Largest size or time an 11-digit octal header field holds. */
const MAX_OCTAL = 0o77777777777;
/** pax records are small; this bounds a damaged or hostile header's claim on memory. */
const MAX_PAX = 1024 * 1024;

export type TarEntry =
  | { type: "file"; path: string; mode: number; mtime: number; size: number; content: AsyncIterable<Buffer> }
  | { type: "directory"; path: string; mode: number; mtime: number }
  | { type: "symlink"; path: string; target: string; mtime: number };

export class TarError extends Error {}

const TYPE_FLAG: Record<TarEntry["type"], string> = { file: "0", directory: "5", symlink: "2" };

function writeString(header: Buffer, offset: number, length: number, value: string): void {
  header.write(value, offset, length, "utf8");
}

function writeOctal(header: Buffer, offset: number, length: number, value: number): void {
  header.write(`${Math.min(value, 8 ** (length - 1) - 1).toString(8).padStart(length - 1, "0")}\0`, offset, length, "ascii");
}

/** One ustar header block. Fields that do not fit are carried by a preceding pax header instead. */
function ustarHeader(name: string, type: string, size: number, mode: number, mtime: number, linkname = ""): Buffer {
  const header = Buffer.alloc(BLOCK);
  writeString(header, 0, 100, name);
  writeOctal(header, 100, 8, mode & 0o7777);
  writeOctal(header, 108, 8, 0);
  writeOctal(header, 116, 8, 0);
  writeOctal(header, 124, 12, size);
  writeOctal(header, 136, 12, Math.max(0, Math.floor(mtime)));
  header.write(" ".repeat(8), 148, 8, "ascii");
  header.write(type, 156, 1, "ascii");
  writeString(header, 157, 100, linkname);
  header.write("ustar\u000000", 257, 8, "ascii");
  let sum = 0;
  for (const byte of header) sum += byte;
  header.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148, 8, "ascii");
  return header;
}

/** A pax record: its decimal length counts the whole record, length digits included. */
function paxRecord(key: string, value: string): string {
  const body = ` ${key}=${value}\n`;
  let length = Buffer.byteLength(body);
  while (String(length).length + Buffer.byteLength(body) !== length) length = String(length).length + Buffer.byteLength(body);
  return `${length}${body}`;
}

function padding(size: number): Buffer {
  return Buffer.alloc((BLOCK - (size % BLOCK)) % BLOCK);
}

/** A ustar field holds only short ASCII; anything else goes in a pax header. */
function fitsUstar(value: string, length: number): boolean {
  return value.length < length && /^[\x20-\x7e]*$/.test(value);
}

/** The tar stream for `entries`, ending with the two zero blocks. A file shorter than its `size` is padded with zeros. */
export async function* tarStream(entries: AsyncIterable<TarEntry>): AsyncGenerator<Buffer> {
  for await (const entry of entries) {
    const name = entry.type === "directory" ? `${entry.path}/` : entry.path;
    const size = entry.type === "file" ? entry.size : 0;
    const target = entry.type === "symlink" ? entry.target : "";
    const mode = entry.type === "symlink" ? 0o777 : entry.mode;
    let pax = "";
    if (!fitsUstar(name, 100)) pax += paxRecord("path", name);
    if (!fitsUstar(target, 100)) pax += paxRecord("linkpath", target);
    if (size > MAX_OCTAL) pax += paxRecord("size", String(size));
    if (pax.length > 0) {
      const records = Buffer.from(pax);
      yield ustarHeader("PaxHeader", "x", records.length, 0o644, entry.mtime);
      yield records;
      yield padding(records.length);
    }
    yield ustarHeader(fitsUstar(name, 100) ? name : "", TYPE_FLAG[entry.type], size, mode, entry.mtime, fitsUstar(target, 100) ? target : "");
    if (entry.type !== "file") continue;
    let written = 0;
    for await (const chunk of entry.content) {
      if (written >= size) break;
      const part = chunk.length > size - written ? chunk.subarray(0, size - written) : chunk;
      written += part.length;
      yield part;
    }
    if (written < size) yield Buffer.alloc(size - written);
    yield padding(size);
  }
  yield Buffer.alloc(BLOCK * 2);
}

/** Reads exact byte counts from a chunked stream. */
class ByteReader {
  private readonly iterator: AsyncIterator<Buffer>;
  private chunk: Buffer = Buffer.alloc(0);

  constructor(source: AsyncIterable<Buffer>) {
    this.iterator = source[Symbol.asyncIterator]();
  }

  /** Up to `max` bytes, at least one; null at the end of the stream. */
  async some(max: number): Promise<Buffer | null> {
    while (this.chunk.length === 0) {
      const next = await this.iterator.next();
      if (next.done === true) return null;
      this.chunk = next.value;
    }
    const part = this.chunk.subarray(0, max);
    this.chunk = this.chunk.subarray(part.length);
    return part;
  }

  async exactly(length: number): Promise<Buffer> {
    const parts: Buffer[] = [];
    let remaining = length;
    while (remaining > 0) {
      const part = await this.some(remaining);
      if (part === null) throw new TarError("The archive ends in the middle of an entry");
      parts.push(part);
      remaining -= part.length;
    }
    return parts.length === 1 ? (parts[0] as Buffer) : Buffer.concat(parts);
  }

  async skip(length: number): Promise<void> {
    let remaining = length;
    while (remaining > 0) {
      const part = await this.some(remaining);
      if (part === null) throw new TarError("The archive ends in the middle of an entry");
      remaining -= part.length;
    }
  }

  /** Reads to the end, so a gzip source verifies its checksum; throws on anything but zeros. */
  async drain(): Promise<void> {
    for (;;) {
      const part = await this.some(64 * 1024);
      if (part === null) return;
      if (part.some((byte) => byte !== 0)) throw new TarError("The archive has data after its end");
    }
  }
}

function readString(header: Buffer, offset: number, length: number): string {
  const field = header.subarray(offset, offset + length);
  const end = field.indexOf(0);
  return field.subarray(0, end < 0 ? length : end).toString("utf8");
}

function readOctal(header: Buffer, offset: number, length: number): number {
  const text = header.subarray(offset, offset + length).toString("ascii").replace(/[\0 ]+$/, "").trim();
  if (text === "") return 0;
  if (!/^[0-7]+$/.test(text)) throw new TarError("The archive has a damaged header");
  return Number.parseInt(text, 8);
}

function checkHeader(header: Buffer): void {
  let sum = 0;
  for (let index = 0; index < BLOCK; index++) sum += index >= 148 && index < 156 ? 0x20 : (header[index] as number);
  if (readOctal(header, 148, 8) !== sum) throw new TarError("The archive has a damaged header");
  if (header.subarray(257, 262).toString("ascii") !== "ustar") throw new TarError("The file is not a tar archive");
}

function parsePax(records: Buffer): Record<string, string> {
  const values: Record<string, string> = {};
  let offset = 0;
  while (offset < records.length) {
    const space = records.indexOf(0x20, offset);
    const length = Number(records.subarray(offset, space).toString("ascii"));
    if (space < 0 || !Number.isInteger(length) || length <= 0 || offset + length > records.length) {
      throw new TarError("The archive has a damaged pax header");
    }
    const record = records.subarray(space + 1, offset + length - 1).toString("utf8");
    const equals = record.indexOf("=");
    if (equals < 0) throw new TarError("The archive has a damaged pax header");
    values[record.slice(0, equals)] = record.slice(equals + 1);
    offset += length;
  }
  return values;
}

/**
 * The entries of a tar stream. A file's `content` must be read to its end, or left unread, before asking for the next
 * entry. Only files, folders and symlinks are accepted; any other kind, a damaged header or a truncated stream throws
 * `TarError`. After the end marker the rest of the stream is read, so a gzip source checks its CRC.
 */
export async function* readTar(source: AsyncIterable<Buffer>): AsyncGenerator<TarEntry> {
  const reader = new ByteReader(source);
  let pax: Record<string, string> = {};
  for (;;) {
    const header = await reader.exactly(BLOCK);
    if (header.every((byte) => byte === 0)) {
      await reader.drain();
      return;
    }
    checkHeader(header);
    const type = String.fromCharCode(header[156] as number);
    const prefix = readString(header, 345, 155);
    const headerName = readString(header, 0, 100);
    const path = (pax.path ?? (prefix === "" ? headerName : `${prefix}/${headerName}`)).replace(/\/+$/, "");
    const target = pax.linkpath ?? readString(header, 157, 100);
    const size = pax.size === undefined ? readOctal(header, 124, 12) : Number(pax.size);
    if (!Number.isSafeInteger(size) || size < 0) throw new TarError("The archive has a damaged header");
    const mode = readOctal(header, 100, 8);
    const mtime = pax.mtime === undefined ? readOctal(header, 136, 12) : Math.floor(Number(pax.mtime));
    pax = {};

    if (type === "x") {
      if (size > MAX_PAX) throw new TarError("The archive has an oversized pax header");
      pax = parsePax(await reader.exactly(size));
      await reader.skip((BLOCK - (size % BLOCK)) % BLOCK);
      continue;
    }
    if (type === "g") {
      await reader.skip(size + ((BLOCK - (size % BLOCK)) % BLOCK));
      continue;
    }
    if (type === "5") {
      yield { type: "directory", path, mode, mtime };
      await reader.skip(size + ((BLOCK - (size % BLOCK)) % BLOCK));
      continue;
    }
    if (type === "2") {
      yield { type: "symlink", path, target, mtime };
      continue;
    }
    if (type !== "0" && type !== "\0") throw new TarError(`The archive holds an unsupported entry: ${path}`);

    let remaining = size;
    async function* content(): AsyncGenerator<Buffer> {
      while (remaining > 0) {
        const part = await reader.some(Math.min(remaining, 64 * 1024));
        if (part === null) throw new TarError("The archive ends in the middle of an entry");
        remaining -= part.length;
        yield part;
      }
    }
    yield { type: "file", path, mode, mtime, size, content: content() };
    await reader.skip(remaining + ((BLOCK - (size % BLOCK)) % BLOCK));
  }
}
