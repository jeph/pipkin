import { spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import fsp from "node:fs/promises";
import { STATUS_CODES } from "node:http";
import https from "node:https";
import type { Duplex } from "node:stream";
import path from "node:path";
import { StringDecoder } from "node:string_decoder";
import { setTimeout as delay } from "node:timers/promises";
import { createInvalidRequestResponse, createJSONRPCNotification, isJSONRPCID, JSONRPCErrorCode, JSONRPCErrorException, JSONRPCServer, type JSONRPCID, type JSONRPCRequest, type JSONRPCResponse } from "json-rpc-2.0";
import { WebSocket, WebSocketServer, type RawData } from "ws";
import { PROTOCOL, RPC_PATH, RPC_PROTOCOL, tokenMatches, type Credentials } from "./auth.js";
import { Journal } from "./journal.js";
import { PI_PACKAGE } from "./pi.js";
import { spawnOwned, stopOwned, type Owned } from "./owned.js";
import { Frames, isCount, isObject, need, parseJson, Refusal, WIRE, type JsonObject } from "./wire.js";

export interface DaemonConfig {
  host: string;
  port: number;
  installId: string;
  credentials: Credentials;
  root: string;
  prefix: string;
  /** Login shell for Pi's bash tool (`settings.shellPath`). */
  shell: string;
  uid: number;
  /** Inherited only by Pi and npm; never the daemon's own environment. */
  environment: Record<string, string>;
  npm: string;
  pi: { command: string; args: readonly string[] };
  piVersion: string;
  idleExitMs: number;
  idlePollMs: number;
  /** Descendant tracking interval for owned processes. */
  trackMs: number;
}

export class StartupFailure extends Error {
  constructor(
    readonly code: number,
    message: string,
  ) {
    super(message);
  }
}

export const EXIT_PORT_IN_USE = 78;
export const EXIT_LOCK_HELD = 79;

const MAX_UNAUTHENTICATED = 4;
const AUTH_TIMEOUT_MS = 10_000;
const MAX_ACTIVE_REQUESTS = 128;
const MAX_RECEIPTS = 1024;
const MAX_RUNNING = 4;
const RETAINED_EXITED = 16;
const NPM_TIMEOUT_MS = 900_000;
const STDIN_WRITE_TIMEOUT_MS = 15_000;
const WORKSPACE = /^[A-Za-z0-9._-]{1,64}$/;
const RECEIPT_METHODS: Record<string, true> = { "runtime.ensure": true, "agent.start": true, "agent.send": true, "agent.close": true, shutdown: true };

interface Client {
  readonly socket: WebSocket;
}

interface RequestContext {
  readonly client: Client;
}

interface Generation {
  readonly generation: number;
  readonly workspace: string;
  readonly owned: Owned;
  readonly startedAt: number;
  readonly journal: Journal;
  state: "running" | "exited";
  sendWork: Promise<void>;
}

interface Receipt {
  id: JSONRPCID;
  method: string;
  outcome: "accepted" | "rejected";
}

interface ProcessIdentity {
  pid: number;
  start: string;
  uid: number;
}

function identity(pid: number): ProcessIdentity | null {
  try {
    const text = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    const fields = text.slice(text.lastIndexOf(") ") + 2).trim().split(/\s+/);
    const owner = /^Uid:\s+(\d+)/m.exec(fs.readFileSync(`/proc/${pid}/status`, "utf8"))?.[1];
    const start = fields[19];
    if (start === undefined || owner === undefined) return null;
    return { pid, start, uid: Number(owner) };
  } catch {
    return null;
  }
}

async function atomicWrite(file: string, text: string): Promise<void> {
  const temporary = path.join(path.dirname(file), `.tmp-${randomUUID()}`);
  const handle = await fsp.open(temporary, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL, 0o600);
  try {
    await handle.writeFile(text);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await fsp.rename(temporary, file);
}

export class Daemon {
  readonly instance = randomUUID();
  readonly startedAt = Date.now();
  private readonly server: https.Server;
  private readonly websockets: WebSocketServer;
  private readonly rpc = new JSONRPCServer<RequestContext>({ errorListener: () => undefined });
  private readonly sockets = new Set<Duplex>();
  private readonly clients = new Set<Client>();
  private active: Client | null = null;
  private readonly generations = new Map<number, Generation>();
  private readonly receipts: Receipt[] = [];
  private readonly requestIds = new Set<JSONRPCID>();
  private activeRequests = 0;
  private pendingMessages = 0;
  private pendingBytes = 0;
  private work: Promise<void> = Promise.resolve();
  private readonly inflight = new Set<Promise<void>>();
  private npmInstall: ChildProcess | null = null;
  private idleSince: number | null = null;
  private idleTimer: NodeJS.Timeout | undefined;
  private lockOwned = false;
  private stopping: Promise<void> | null = null;
  private exitCode = 0;

  constructor(private readonly config: DaemonConfig) {
    const { certificate, privateKey, token } = config.credentials;
    const authorization = Buffer.from(`Bearer ${token}`, "utf8");
    this.server = https.createServer({
      cert: certificate,
      key: privateKey,
      minVersion: "TLSv1.2",
      handshakeTimeout: AUTH_TIMEOUT_MS,
      headersTimeout: AUTH_TIMEOUT_MS,
      requestTimeout: AUTH_TIMEOUT_MS,
      maxHeaderSize: 8192,
    }, (_request, response) => {
      response.writeHead(404, { Connection: "close", "Content-Length": 0 });
      response.end();
    });
    // Bound TLS/HTTP handshakes as well as upgraded clients. No application handshake.
    this.server.maxConnections = MAX_UNAUTHENTICATED + 1;
    this.server.maxHeadersCount = 32;
    this.server.on("connection", (socket) => {
      this.sockets.add(socket);
      socket.on("error", () => undefined);
      socket.once("close", () => { this.sockets.delete(socket); });
    });
    this.server.on("secureConnection", (socket) => {
      socket.setTimeout(AUTH_TIMEOUT_MS, () => { socket.destroy(); });
    });
    this.websockets = new WebSocketServer({
      noServer: true,
      maxPayload: WIRE,
      perMessageDeflate: false,
      handleProtocols: () => RPC_PROTOCOL,
    });
    this.server.on("upgrade", (request, socket, head) => {
      const headers = request.headers;
      const rejection = this.stopping !== null ? 503 :
        request.url !== RPC_PATH ? 404 :
        headers.origin !== undefined || headers["sec-websocket-origin"] !== undefined ? 403 :
        request.method !== "GET" || headers["sec-websocket-version"] !== "13" || headers["sec-websocket-protocol"] !== RPC_PROTOCOL ? 400 :
        request.headersDistinct.authorization?.length !== 1 || !tokenMatches(authorization, headers.authorization) ? 401 : null;
      if (rejection !== null) {
        socket.end(`HTTP/1.1 ${rejection} ${STATUS_CODES[rejection]}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
        return;
      }
      this.websockets.handleUpgrade(request, socket, head, (websocket) => {
        request.socket.setTimeout(0);
        request.socket.setNoDelay(true);
        this.accept(websocket);
      });
    });
    for (const method of ["status", "runtime.ensure", "agent.start", "agent.send", "agent.close", "agent.attach", "shutdown"]) {
      this.rpc.addMethod(method, (params: unknown, { client }) => this.dispatch(client, method, params as JsonObject));
    }
    this.rpc.applyMiddleware((next, request, context) => this.request(next, request, context));
  }

  get port(): number {
    const address = this.server.address();
    return typeof address === "object" && address !== null ? address.port : this.config.port;
  }

  /** Resolves once listening; rejects with StartupFailure on lock or port conflicts. */
  async start(): Promise<void> {
    const { root } = this.config;
    for (const child of ["", "runtime/pi", "workspaces", "home", "config", "tmp"]) {
      await fsp.mkdir(path.join(root, child), { recursive: true, mode: 0o700 });
    }
    await this.acquireLock();
    const listening: PromiseWithResolvers<void> = Promise.withResolvers();
    const onError = (error: NodeJS.ErrnoException) => {
      listening.reject(
        error.code === "EADDRINUSE"
          ? new StartupFailure(EXIT_PORT_IN_USE, "Paired port is in use")
          : new StartupFailure(1, error.message),
      );
    };
    this.server.once("error", onError);
    this.server.listen({ host: this.config.host, port: this.config.port, exclusive: true }, () => {
      this.server.off("error", onError);
      listening.resolve();
    });
    await listening.promise;
    this.server.on("error", () => {
      void this.shutdown(1);
    });
    this.idleTimer = setInterval(() => {
      this.checkIdle();
    }, this.config.idlePollMs);
  }

  private async acquireLock(): Promise<void> {
    const lock = path.join(this.config.root, ".daemon-lock");
    const owner = path.join(lock, "owner.json");
    let prior: unknown = null;
    try {
      prior = parseJson(await fsp.readFile(owner));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") {
        await fsp.rm(lock, { recursive: true, force: true });
      }
    }
    if (isObject(prior) && isCount(prior.pid) && typeof prior.start === "string" && prior.uid === this.config.uid) {
      const live = identity(prior.pid);
      if (live !== null && live.start === prior.start && live.uid === prior.uid) {
        throw new StartupFailure(EXIT_LOCK_HELD, "Another supervisor owns this installation");
      }
    }
    await fsp.rm(lock, { recursive: true, force: true });
    const self = identity(process.pid) ?? { pid: process.pid, start: String(this.startedAt), uid: this.config.uid };
    const candidate = await fsp.mkdtemp(path.join(this.config.root, "tmp", ".lock-"));
    await atomicWrite(path.join(candidate, "owner.json"), JSON.stringify(self));
    await fsp.rename(candidate, lock);
    this.lockOwned = true;
  }

  private accept(socket: WebSocket): void {
    if (this.stopping !== null) {
      socket.terminate();
      return;
    }
    const client: Client = { socket };
    this.clients.add(client);
    socket.on("error", () => undefined);
    socket.on("close", () => {
      this.clients.delete(client);
      if (this.active === client) this.active = null;
    });
    socket.on("message", (data: RawData, binary: boolean) => {
      if (client !== this.active || binary) {
        socket.close(1008, "Only the active text RPC connection may send requests");
        return;
      }
      const bytes = Array.isArray(data) ? data.reduce((sum, part) => sum + part.length, 0) : data.byteLength;
      if (this.pendingMessages >= MAX_ACTIVE_REQUESTS || this.pendingBytes + bytes > 2 * WIRE) {
        socket.terminate();
        return;
      }
      this.pendingMessages++;
      this.pendingBytes += bytes;
      const text = (Array.isArray(data) ? Buffer.concat(data) : Buffer.isBuffer(data) ? data : Buffer.from(data)).toString("utf8");
      const completion = this.receive(client, text).finally(() => {
        this.pendingMessages--;
        this.pendingBytes -= bytes;
        this.inflight.delete(completion);
      });
      this.inflight.add(completion);
    });
    const previous = this.active;
    // Revoke authority before notifying or closing the old socket. Already-dispatched
    // operations finish, but its queued operations check authority at execution time.
    this.active = client;
    this.idleSince = null;
    if (previous !== null) {
      this.send(previous, JSON.stringify(createJSONRPCNotification("agent.event", { event: "superseded" })));
      previous.socket.close(1000, "Superseded");
      setTimeout(() => { previous.socket.terminate(); }, 1000).unref();
    }
  }

  /** The library owns RPC validation, method lookup, notifications and response envelopes. */
  private async receive(client: Client, text: string): Promise<void> {
    let value: unknown;
    let response: JSONRPCResponse | JSONRPCResponse[] | null;
    try {
      value = JSON.parse(text) as unknown;
    } catch {
      response = await this.rpc.receiveJSON(text, { client });
      if (response !== null) this.send(client, JSON.stringify(response));
      return;
    }
    // json-rpc-2.0's predicate dereferences null. Normalize only that unsupported
    // shape to an invalid object, including batch members; the library rejects it.
    if (Array.isArray(value)) {
      if (value.length > MAX_ACTIVE_REQUESTS) {
        client.socket.terminate();
        return;
      }
      response = await this.rpc.receive(value.map((entry: unknown) => entry ?? {}) as JSONRPCRequest[], { client });
      // The library unwraps singleton responses; JSON-RPC requires a batch response
      // to remain an array, except for the special empty-batch invalid-request error.
      if (value.length > 0 && response !== null && !Array.isArray(response)) response = [response];
    } else {
      response = await this.rpc.receive((value ?? {}) as JSONRPCRequest, { client });
    }
    if (response !== null) this.send(client, JSON.stringify(response));
  }

  /** All live and replay traffic shares the same byte-based backpressure limit. */
  private send(client: Client, message: string, bytes = Buffer.byteLength(message)): void {
    if (client.socket.readyState !== WebSocket.OPEN) return;
    if (bytes > WIRE || client.socket.bufferedAmount + bytes > 2 * WIRE) {
      client.socket.terminate();
      return;
    }
    client.socket.send(message, { binary: false }, (error) => {
      if (error) client.socket.terminate();
    });
  }

  private emit(event: string, fields: JsonObject): void {
    if (this.active !== null) this.send(this.active, JSON.stringify(createJSONRPCNotification("agent.event", { event, ...fields })));
  }

  private journal(generation: Generation, event: string, fields: JsonObject): void {
    const entry = generation.journal.append({ event, generation: generation.generation, ...fields });
    if (this.active !== null) this.send(this.active, entry.message, entry.bytes);
  }

  private request(
    next: (request: JSONRPCRequest, context: RequestContext) => PromiseLike<JSONRPCResponse | null>,
    request: JSONRPCRequest,
    context: RequestContext,
  ): Promise<JSONRPCResponse | null> {
    const { id, method } = request;
    const params: unknown = request.params;
    // Supplemental checks for types the library's intentionally loose predicate omits.
    if (typeof method !== "string" || (id !== undefined && (!isJSONRPCID(id) || (typeof id === "number" && !Number.isFinite(id)))) ||
        (params !== undefined && !isObject(params) && !Array.isArray(params))) {
      return Promise.resolve(createInvalidRequestResponse(request));
    }
    if ((typeof id === "string" && id.length > 256) ||
        (id !== undefined && this.requestIds.has(id)) || this.activeRequests >= MAX_ACTIVE_REQUESTS) {
      context.client.socket.terminate();
      return Promise.resolve(null);
    }
    if (id !== undefined) this.requestIds.add(id);
    this.activeRequests++;
    const execute = async (): Promise<JSONRPCResponse | null> => {
      need(context.client === this.active && context.client.socket.readyState === WebSocket.OPEN, "Connection is no longer active");
      need(this.stopping === null, "Supervisor is shutting down");
      if (this.rpc.hasMethod(method) && params !== undefined && !isObject(params)) {
        throw new JSONRPCErrorException("Named parameters are required", JSONRPCErrorCode.InvalidParams, { outcome: "rejected" });
      }
      return next({ ...request, params: params ?? {} }, context);
    };
    // Close is never held behind stdin backpressure. Pi events are independent of
    // these queues, so the client can answer extension prompts during a long operation.
    let operation: Promise<JSONRPCResponse | null>;
    if (method === "agent.close") {
      operation = this.work.then(execute);
    } else if (method === "agent.send") {
      operation = this.work.then(() => {
        // Resolve the generation after preceding agent.start has completed, so
        // pipelined sends to a new generation also share its ordered stdin queue.
        const generation = isObject(params) && isCount(params.generation) ? this.generations.get(params.generation) : undefined;
        const send = (generation?.sendWork ?? Promise.resolve()).then(execute);
        if (generation !== undefined) generation.sendWork = send.then(() => undefined, () => undefined);
        return send;
      });
    } else {
      operation = this.work.then(execute);
      this.work = operation.then(() => undefined, () => undefined);
    }
    return operation.then(
      (response) => {
        if (id !== undefined && response?.error === undefined) this.record(id, method, "accepted");
        return response;
      },
      (failure: unknown) => {
        if (failure instanceof JSONRPCErrorException) throw failure;
        const refusal = failure instanceof Refusal;
        if (refusal && id !== undefined) this.record(id, method, "rejected");
        throw new JSONRPCErrorException(
          refusal ? failure.message.slice(0, 256) : "Daemon operation failed; delivery is uncertain and no mutation was retried",
          refusal ? -32000 : -32001,
          { outcome: refusal ? "rejected" : "uncertain" },
        );
      },
    ).finally(() => {
      if (id !== undefined) this.requestIds.delete(id);
      this.activeRequests--;
    });
  }

  private record(id: JSONRPCID, method: string, outcome: Receipt["outcome"]): void {
    if (RECEIPT_METHODS[method] !== true) return;
    this.receipts.push({ id, method, outcome });
    if (this.receipts.length > MAX_RECEIPTS) this.receipts.splice(0, this.receipts.length - MAX_RECEIPTS);
  }

  private async dispatch(client: Client, method: string, params: JsonObject): Promise<JsonObject> {
    switch (method) {
      case "status":
        return this.status();
      case "runtime.ensure":
        return this.ensureRuntime();
      case "agent.start":
        return this.startAgent(params);
      case "agent.send":
        return this.sendAgent(params);
      case "agent.close":
        return this.closeAgent(params);
      case "agent.attach":
        return this.attachAgent(client, params);
      case "shutdown":
        // Respond first; the exit path runs after the response has been queued.
        setImmediate(() => {
          void this.shutdown(0);
        });
        return {};
      default:
        throw new Error("Unregistered daemon method");
    }
  }

  private installedPiVersion(): string | null {
    try {
      const manifest = parseJson(
        fs.readFileSync(path.join(this.config.root, "runtime/pi/node_modules", PI_PACKAGE, "package.json")),
      );
      return isObject(manifest) && typeof manifest.version === "string" ? manifest.version : null;
    } catch {
      return null;
    }
  }

  private status(): JsonObject {
    const generations = [...this.generations.values()].map((generation) => ({
      generation: generation.generation,
      workspace: generation.workspace,
      pid: generation.owned.pid,
      startedAt: generation.startedAt,
      lastSeq: generation.journal.lastSeq,
      state: generation.state,
      code: generation.owned.code,
      signal: generation.owned.signal,
    }));
    return {
      protocol: PROTOCOL,
      uid: this.config.uid,
      instance: this.instance,
      pid: process.pid,
      startedAt: this.startedAt,
      root: this.config.root,
      prefix: this.config.prefix,
      nodeVersion: process.version,
      piVersion: this.installedPiVersion(),
      generations,
      receipts: this.receipts,
    };
  }

  private async ensureRuntime(): Promise<JsonObject> {
    const { piVersion, npm, root, environment } = this.config;
    if (this.installedPiVersion() === piVersion) return { piVersion, installed: false };
    need(this.npmInstall === null, "Pi installation is already in progress");
    const child = spawn(npm, ["ci", "--omit=dev", "--no-audit", "--no-fund", "--loglevel=error"], {
      cwd: path.join(root, "runtime/pi"),
      env: environment,
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    this.npmInstall = child;
    const progress = (chunk: Buffer) => {
      for (const text of chunk.toString("utf8").split("\n")) {
        if (text.trim().length > 0) this.emit("progress", { text: text.slice(0, 4096) });
      }
    };
    child.stdout.on("data", progress);
    child.stderr.on("data", progress);
    const timer = setTimeout(() => {
      if (child.pid !== undefined) {
        try {
          process.kill(-child.pid, "SIGKILL");
        } catch {
          // Already gone.
        }
      }
    }, NPM_TIMEOUT_MS);
    try {
      const exited = Promise.withResolvers<number | null>();
      child.once("exit", exited.resolve);
      child.once("error", () => {
        exited.resolve(null);
      });
      const code = await exited.promise;
      if (code !== 0) throw new Error(`Pi installation failed after dispatch (npm exit ${code ?? "error"})`);
    } finally {
      clearTimeout(timer);
      this.npmInstall = null;
    }
    if (this.installedPiVersion() !== piVersion) throw new Error("Installed Pi version mismatch after npm completed");
    return { piVersion, installed: true };
  }

  private async startAgent(params: JsonObject): Promise<JsonObject> {
    const { generation, workspace } = params;
    need(isCount(generation) && generation > 0, "Invalid Pi generation");
    need(typeof workspace === "string" && WORKSPACE.test(workspace), "Invalid workspace name");
    for (const known of this.generations.keys()) need(generation > known, "Pi generation must be newer than every known generation");
    let running = 0;
    for (const known of this.generations.values()) if (known.state === "running") running++;
    need(running < MAX_RUNNING, "Too many running Pi sessions");
    const piVersion = this.installedPiVersion();
    need(piVersion === this.config.piVersion, "Pi runtime is not installed");
    const { root, shell, environment } = this.config;
    const workspaceDir = path.join(root, "workspaces", workspace);
    const sessionsDir = path.join(workspaceDir, "sessions");
    const home = path.join(root, "home");
    const config = path.join(root, "config");
    for (const target of [sessionsDir, ...[".config", ".cache", ".local/share", ".local/state"].map((name) => path.join(home, name))]) {
      await fsp.mkdir(target, { recursive: true, mode: 0o700 });
    }
    await atomicWrite(
      path.join(config, "settings.json"),
      JSON.stringify({
        shellPath: shell,
        packages: [],
        extensions: [],
        skills: [],
        prompts: [],
        themes: [],
        defaultProjectTrust: "never",
        enableInstallTelemetry: false,
        enableAnalytics: false,
        compaction: { enabled: true },
        retry: { enabled: true, maxRetries: 2, provider: { timeoutMs: 60000, maxRetries: 1 } },
      }),
    );
    const env = {
      ...environment,
      XDG_CONFIG_HOME: `${home}/.config`,
      XDG_CACHE_HOME: `${home}/.cache`,
      XDG_DATA_HOME: `${home}/.local/share`,
      XDG_STATE_HOME: `${home}/.local/state`,
      PI_CODING_AGENT_DIR: config,
      PI_SKIP_VERSION_CHECK: "1",
      PI_OFFLINE: "1",
      PI_TELEMETRY: "0",
    };
    const owned = await spawnOwned(this.config.pi.command, [...this.config.pi.args, "--session-dir", sessionsDir], {
      cwd: workspaceDir,
      env,
      trackMs: this.config.trackMs,
    });
    const item: Generation = {
      generation,
      workspace,
      owned,
      startedAt: Date.now(),
      journal: new Journal(),
      state: "running",
      sendWork: Promise.resolve(),
    };
    this.generations.set(generation, item);
    this.idleSince = null;
    const frames = new Frames((record) => {
      this.journal(item, "pi", { record });
    }, WIRE - 1024);
    const fault = (text: string) => {
      this.journal(item, "stderr", { text });
      void stopOwned(owned).catch(() => undefined);
    };
    owned.child.stdout.on("data", (chunk: Buffer) => {
      try {
        frames.push(chunk);
      } catch {
        fault("Pi output exceeded the frame limit; the session was stopped.");
      }
    });
    owned.child.stdout.on("end", () => {
      try {
        frames.finish();
      } catch {
        fault("Pi output ended mid-record.");
      }
    });
    const decoder = new StringDecoder("utf8");
    owned.child.stderr.on("data", (chunk: Buffer) => {
      const text = decoder.write(chunk);
      if (text.length > 0) this.journal(item, "stderr", { text });
    });
    owned.child.stderr.on("end", () => {
      const text = decoder.end();
      if (text.length > 0) this.journal(item, "stderr", { text });
    });
    void owned.exit.then(async () => {
      await stopOwned(owned).catch(() => undefined);
      item.state = "exited";
      this.journal(item, "exit", { code: owned.code, signal: owned.signal });
      this.retire();
    });
    return { pid: owned.pid, piVersion };
  }

  /** Keeps every running generation plus the newest RETAINED_EXITED exited ones. */
  private retire(): void {
    const exited = [...this.generations.values()].filter((item) => item.state === "exited");
    for (const stale of exited.slice(0, Math.max(0, exited.length - RETAINED_EXITED))) {
      this.generations.delete(stale.generation);
    }
  }

  private async sendAgent(params: JsonObject): Promise<JsonObject> {
    const { generation, record } = params;
    const item = isCount(generation) ? this.generations.get(generation) : undefined;
    need(item?.state === "running" && item.owned.closing === null, "Pi generation is not active");
    need(isObject(record) && typeof record.type === "string", "Invalid Pi command");
    const line = JSON.stringify(record) + "\n";
    need(Buffer.byteLength(line) <= WIRE - 1024, "Pi command exceeds frame limit");
    const written: PromiseWithResolvers<void> = Promise.withResolvers();
    const timer = setTimeout(() => {
      written.reject(new Error("Pi stdin write timed out; delivery is uncertain"));
    }, STDIN_WRITE_TIMEOUT_MS);
    item.owned.child.stdin.write(line, (error) => {
      clearTimeout(timer);
      if (error) written.reject(new Error("Pi stdin closed; delivery is uncertain", { cause: error }));
      else written.resolve();
    });
    await written.promise;
    return {};
  }

  private async closeAgent(params: JsonObject): Promise<JsonObject> {
    const { generation } = params;
    const item = isCount(generation) ? this.generations.get(generation) : undefined;
    need(item !== undefined, "Pi generation is not active");
    if (item.state === "exited") return {};
    await stopOwned(item.owned);
    await item.owned.exit;
    return {};
  }

  private attachAgent(client: Client, params: JsonObject): JsonObject {
    const { generation, after } = params;
    const item = isCount(generation) ? this.generations.get(generation) : undefined;
    need(item !== undefined, "Pi generation is not active");
    need(isCount(after), "Invalid journal position");
    const { journal } = item;
    const gap = after + 1 < journal.oldestSeq;
    if (gap) {
      this.send(client, JSON.stringify(createJSONRPCNotification("agent.event", {
        event: "gap", generation: item.generation, from: after + 1, to: journal.oldestSeq - 1,
      })));
    }
    for (const entry of journal.after(after)) this.send(client, entry.message, entry.bytes);
    return { lastSeq: journal.lastSeq, gap };
  }

  private checkIdle(): void {
    let running = false;
    for (const item of this.generations.values()) if (item.state === "running") running = true;
    if (this.active !== null || running) {
      this.idleSince = null;
      return;
    }
    this.idleSince ??= Date.now();
    if (Date.now() - this.idleSince >= this.config.idleExitMs) void this.shutdown(0);
  }

  /** Stops every owned process, releases the lock and exits. Idempotent. */
  shutdown(code: number): Promise<void> {
    this.stopping ??= (async () => {
      this.exitCode = code;
      clearInterval(this.idleTimer);
      this.server.close();
      const npm = this.npmInstall;
      if (npm?.pid !== undefined) {
        try {
          process.kill(-npm.pid, "SIGKILL");
        } catch {
          // Already gone.
        }
      }
      const stops = await Promise.all(
        [...this.generations.values()]
          .filter((item) => item.state === "running")
          .map((item) => stopOwned(item.owned).then(() => true, () => false)),
      );
      await Promise.allSettled([this.work, ...this.inflight]);
      for (const client of this.clients) client.socket.close(1000, "Supervisor stopped");
      await delay(50);
      for (const client of this.clients) client.socket.terminate();
      this.websockets.close();
      for (const socket of this.sockets) socket.destroy();
      let failed = stops.includes(false);
      if (this.lockOwned && !failed) {
        failed = !(await fsp.rm(path.join(this.config.root, ".daemon-lock"), { recursive: true, force: true }).then(() => true, () => false));
      }
      if (failed && this.exitCode === 0) this.exitCode = 1;
    })();
    return this.stopping;
  }

  get exitStatus(): number {
    return this.exitCode;
  }
}
