import { randomBytes, randomUUID } from "node:crypto";
import type { IncomingHttpHeaders } from "node:http";
import { generate } from "selfsigned";
import WebSocket from "ws";
import { isObject, type JsonObject } from "../src/wire.js";

export interface TestCredentials {
  token: string;
  privateKey: string;
  certificate: string;
}

/** Real loopback certificates: clients trust this certificate, never bypass TLS verification. */
export async function testCredentials(): Promise<TestCredentials> {
  const certificate = await generate([{ name: "commonName", value: "localhost" }], {
    keyType: "ec",
    algorithm: "sha256",
    notBeforeDate: new Date(Date.now() - 60_000),
    extensions: [
      { name: "basicConstraints", cA: false },
      { name: "keyUsage", digitalSignature: true },
      { name: "extKeyUsage", serverAuth: true },
      { name: "subjectAltName", altNames: [{ type: 2, value: "localhost" }, { type: 7, ip: "127.0.0.1" }] },
    ],
  });
  return { token: randomBytes(32).toString("hex"), privateKey: certificate.private, certificate: certificate.cert };
}

export interface ConnectOptions {
  ca?: string;
  token?: string | null;
  pathname?: string;
  protocols?: string[];
  origin?: string;
}

export class UpgradeRejection extends Error {
  constructor(readonly status: number | undefined, readonly headers: IncomingHttpHeaders) {
    super(`WebSocket upgrade rejected with HTTP ${String(status)}`);
  }
}

export class TestClient {
  private readonly queue: JsonObject[] = [];
  private readonly batches: JsonObject[][] = [];
  private arrived: PromiseWithResolvers<void> = Promise.withResolvers();
  private readonly prefix = randomUUID();
  private sequence = 0;
  private failure: Error | undefined;
  closed = false;
  readonly closedPromise: Promise<void>;

  private constructor(readonly socket: WebSocket) {
    const closed: PromiseWithResolvers<void> = Promise.withResolvers();
    this.closedPromise = closed.promise;
    socket.on("message", (bytes, binary) => {
      try {
        if (binary) throw new Error("Expected a JSON-RPC text message");
        const buffer = Array.isArray(bytes) ? Buffer.concat(bytes) : Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
        const record: unknown = JSON.parse(buffer.toString("utf8"));
        const records: unknown[] = Array.isArray(record) ? record : [record];
        const decoded: JsonObject[] = [];
        for (const entry of records) {
          if (!isObject(entry) || entry.jsonrpc !== "2.0") throw new Error("Expected a JSON-RPC 2.0 object");
          decoded.push(entry);
        }
        if (Array.isArray(record)) this.batches.push(decoded);
        else this.queue.push(...decoded);
      } catch (error) {
        this.failure = error instanceof Error ? error : new Error(String(error));
      }
      this.arrived.resolve();
      this.arrived = Promise.withResolvers();
    });
    socket.on("error", (error) => {
      this.failure = error;
      this.arrived.resolve();
    });
    socket.on("close", () => {
      this.closed = true;
      closed.resolve();
      this.arrived.resolve();
    });
  }

  static async connect(port: number, credentials: TestCredentials, options: ConnectOptions = {}): Promise<TestClient> {
    const token = options.token === undefined ? credentials.token : options.token;
    const headers: Record<string, string> = {};
    if (token !== null) headers.Authorization = `Bearer ${token}`;
    if (options.origin !== undefined) headers.Origin = options.origin;
    const socket = new WebSocket(`wss://127.0.0.1:${port}${options.pathname ?? "/rpc"}`, options.protocols ?? ["pipkin.rpc.v1"], {
      ca: options.ca ?? credentials.certificate,
      rejectUnauthorized: true,
      followRedirects: false,
      headers,
    });
    const client = new TestClient(socket);
    const opened: PromiseWithResolvers<void> = Promise.withResolvers();
    socket.once("open", () => { opened.resolve(); });
    socket.once("error", opened.reject);
    socket.once("unexpected-response", (_request, response) => {
      opened.reject(new UpgradeRejection(response.statusCode, response.headers));
      response.resume();
      socket.terminate();
    });
    await opened.promise;
    return client;
  }

  next(): Promise<JsonObject> {
    return this.until(() => true);
  }

  /** Unmatched messages stay queued; close/error wakes readers instead of hiding a failed transport. */
  async until(predicate: (record: JsonObject) => boolean): Promise<JsonObject> {
    for (;;) {
      const index = this.queue.findIndex(predicate);
      const record = this.queue[index];
      if (record !== undefined) {
        this.queue.splice(index, 1);
        return record;
      }
      if (this.failure !== undefined) throw this.failure;
      if (this.closed) throw new Error("WebSocket closed before the expected message");
      await this.arrived.promise;
    }
  }

  async nextBatch(): Promise<JsonObject[]> {
    for (;;) {
      const batch = this.batches.shift();
      if (batch !== undefined) return batch;
      if (this.failure !== undefined) throw this.failure;
      if (this.closed) throw new Error("WebSocket closed before the expected batch");
      await this.arrived.promise;
    }
  }

  async nextEvent(event: string): Promise<JsonObject> {
    const record = await this.until((message) => message.method === "agent.event" && message.id === undefined && isObject(message.params) && message.params.event === event);
    if (!isObject(record.params)) throw new Error("Expected notification parameters");
    return record.params;
  }

  send(record: JsonObject | unknown[]): void {
    this.socket.send(JSON.stringify(record));
  }

  request(method: string, params: JsonObject = {}): Promise<JsonObject> {
    const id = `${this.prefix}-${++this.sequence}`;
    this.send({ jsonrpc: "2.0", id, method, params });
    return this.until((record) => record.id === id);
  }

  async result(method: string, params: JsonObject = {}): Promise<JsonObject> {
    const response = await this.request(method, params);
    if (response.error !== undefined) throw new Error(`${method} rejected: ${JSON.stringify(response.error)}`);
    if (!isObject(response.result)) throw new Error(`${method} did not return an object result`);
    return response.result;
  }
}
