/**
 * The client side of `pipkin-helper/1` (Pipkin's side, also used by the tests) and `node helper.mjs client`, a
 * developer CLI that launches a command through a running helper, e.g. over `adb forward`.
 */
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { connect, type Socket } from "node:net";
import { constants } from "node:os";
import type { JsonObject } from "../framing.js";
import { HelperChannel } from "./channel.js";
import {
  ByteQueue,
  clientMac,
  macEquals,
  parseKeyHex,
  NONCE_BYTES,
  PROTOCOL_VERSION,
  SERVER_HELLO_BYTES,
  SERVER_MAGIC,
  serverMac,
  sessionKeys,
} from "./protocol.js";

/** Something other than a Pipkin helper holds the port. */
export class HelperForeignError extends Error {}

/** A Pipkin helper answered, but with none of the client's keys. */
export class HelperKeyMismatchError extends Error {}

export interface HelperConnection {
  /** Index into `keys` of the key the helper proved it holds. */
  keyIndex: number;
  send(message: JsonObject): void;
  /** The next message from the helper; rejects once the connection is closed. */
  next(): Promise<JsonObject>;
  close(): void;
}

export interface ConnectOptions {
  port: number;
  keys: Buffer[];
  host?: string;
  /** How long the server hello may take before the port counts as foreign. */
  helloTimeoutMs?: number;
}

export function connectHelper({
  port,
  keys,
  host = "127.0.0.1",
  helloTimeoutMs = 3000,
}: ConnectOptions): Promise<HelperConnection> {
  const { promise, resolve, reject } = Promise.withResolvers<HelperConnection>();
  const socket = connect({ port, host });
  const nonceC = randomBytes(32);
  const queue = new ByteQueue();
  const fail = (error: Error): void => {
    clearTimeout(timer);
    socket.destroy();
    reject(error);
  };
  const timer = setTimeout(() => fail(new HelperForeignError(`No helper answered on port ${port}`)), helloTimeoutMs);
  socket.on("connect", () => socket.write(Buffer.concat([Buffer.of(PROTOCOL_VERSION), nonceC])));
  socket.on("error", (error) => fail(error));
  socket.on("close", () => fail(new HelperForeignError(`Port ${port} closed during the handshake`)));
  socket.on("data", (chunk: Buffer) => {
    queue.push(chunk);
    const magic = queue.peek(SERVER_MAGIC.length);
    if (magic !== undefined && !magic.equals(SERVER_MAGIC)) {
      fail(new HelperForeignError(`Port ${port} is held by something else`));
      return;
    }
    const hello = queue.take(SERVER_HELLO_BYTES);
    if (hello === undefined) return;
    clearTimeout(timer);
    const nonceS = hello.subarray(SERVER_MAGIC.length, SERVER_MAGIC.length + NONCE_BYTES);
    const mac = hello.subarray(SERVER_MAGIC.length + NONCE_BYTES);
    const keyIndex = keys.findIndex((key) => macEquals(serverMac(key, nonceC, nonceS), mac));
    const key = keys[keyIndex];
    if (key === undefined) {
      fail(new HelperKeyMismatchError(`The helper on port ${port} has a different key`));
      return;
    }
    socket.removeAllListeners("close");
    socket.write(clientMac(key, nonceC, nonceS));
    const { c2s, s2c } = sessionKeys(key, nonceC, nonceS);
    resolve(clientConnection(socket, c2s, s2c, keyIndex, queue.rest()));
  });
  return promise;
}

function clientConnection(
  socket: Socket,
  c2s: Buffer,
  s2c: Buffer,
  keyIndex: number,
  leftover: Buffer,
): HelperConnection {
  const received: JsonObject[] = [];
  const waiting: { resolve: (message: JsonObject) => void; reject: (error: Error) => void }[] = [];
  let closed = false;
  const channel = new HelperChannel(socket, c2s, s2c, (message) => {
    const waiter = waiting.shift();
    if (waiter === undefined) received.push(message);
    else waiter.resolve(message);
  });
  socket.on("close", () => {
    closed = true;
    for (const waiter of waiting.splice(0)) waiter.reject(new Error("The helper closed the connection"));
  });
  channel.receive(leftover);
  return {
    keyIndex,
    send: (message) => channel.send(message),
    next: () => {
      const message = received.shift();
      if (message !== undefined) return Promise.resolve(message);
      if (closed) return Promise.reject(new Error("The helper closed the connection"));
      const { promise, resolve, reject } = Promise.withResolvers<JsonObject>();
      waiting.push({ resolve, reject });
      return promise;
    },
    close: () => socket.destroy(),
  };
}

const USAGE =
  "Usage: node helper.mjs client --port N --key-file F -- <bash args…>\n" +
  "       node helper.mjs client --port N --key-file F --package P   (bring P to the front)\n";

interface ClientArgs {
  port: number;
  keyFile: string;
  pkg: string | undefined;
  bashArgs: string[] | undefined;
}

function parseClientArgs(argv: string[]): ClientArgs | undefined {
  let port: number | undefined;
  let keyFile: string | undefined;
  let pkg: string | undefined;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--") return finishArgs(port, keyFile, pkg, argv.slice(i + 1));
    const value = argv[++i];
    if (value === undefined) return undefined;
    if (arg === "--port") port = Number.parseInt(value, 10);
    else if (arg === "--key-file") keyFile = value;
    else if (arg === "--package") pkg = value;
    else return undefined;
  }
  return finishArgs(port, keyFile, pkg, undefined);
}

function finishArgs(
  port: number | undefined,
  keyFile: string | undefined,
  pkg: string | undefined,
  bashArgs: string[] | undefined,
): ClientArgs | undefined {
  if (port === undefined || !Number.isInteger(port) || keyFile === undefined) return undefined;
  if ((bashArgs === undefined) === (pkg === undefined)) return undefined;
  return { port, keyFile, pkg, bashArgs };
}

async function readStdin(): Promise<string> {
  if (process.stdin.isTTY) return "";
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf8");
}

/** Runs `node helper.mjs client …`; resolves to the process exit code (the child's, for a launch). */
export async function runClient(argv: string[]): Promise<number> {
  const args = parseClientArgs(argv);
  if (args === undefined) {
    process.stderr.write(USAGE);
    return 2;
  }
  const key = parseKeyHex(readFileSync(args.keyFile, "utf8"));
  if (key === undefined) {
    process.stderr.write(`${args.keyFile} doesn't hold a 64-character hex key\n`);
    return 2;
  }
  const stdin = args.bashArgs === undefined ? "" : await readStdin();
  const connection = await connectHelper({ port: args.port, keys: [key] });
  try {
    if (args.bashArgs === undefined) {
      connection.send({ type: "return", package: args.pkg });
      const reply = await connection.next();
      process.stdout.write(`returned ok=${String(reply.ok)}\n`);
      return reply.ok === true ? 0 : 1;
    }
    connection.send({ type: "launch", args: args.bashArgs, stdin });
    for (;;) {
      const reply = await connection.next();
      if (reply.type === "started") {
        process.stdout.write(`started pid=${String(reply.pid)}\n`);
      } else if (reply.type === "exit") {
        if (typeof reply.stderr === "string") process.stderr.write(reply.stderr);
        process.stdout.write(`exit code=${String(reply.code)} signal=${String(reply.signal)}\n`);
        return exitCodeOf(reply);
      } else {
        process.stderr.write(`${JSON.stringify(reply)}\n`);
        return 1;
      }
    }
  } finally {
    connection.close();
  }
}

function exitCodeOf(reply: JsonObject): number {
  if (typeof reply.code === "number") return reply.code;
  const signal: number | undefined = constants.signals[reply.signal as NodeJS.Signals];
  return 128 + (signal ?? 0);
}
