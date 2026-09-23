import fsp from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { Daemon, EXIT_PORT_IN_USE, StartupFailure, type DaemonConfig } from "../src/server.js";
import type { JsonObject } from "../src/wire.js";
import { TestClient, testCredentials, UpgradeRejection, type ConnectOptions, type TestCredentials } from "./client.js";

const INSTALL_ID = "11111111-2222-4333-8444-555555555555";
const PI_VERSION = "0.85.1";
const FAKE_PI = path.join(import.meta.dirname, "fixtures/fake-pi.mjs");

describe("Daemon", () => {
  let root: string;
  let credentials: TestCredentials;
  let daemon: Daemon;
  let config: DaemonConfig;
  let manifest: string;

  beforeEach(async () => {
    root = await fsp.mkdtemp(path.join(os.tmpdir(), "pipkin-daemon-"));
    credentials = await testCredentials();
    manifest = path.join(root, "runtime/pi/node_modules/@earendil-works/pi-coding-agent/package.json");
    await fsp.mkdir(path.dirname(manifest), { recursive: true });
    await fsp.writeFile(manifest, JSON.stringify({ version: PI_VERSION }));
    config = {
      host: "127.0.0.1",
      port: 0,
      installId: INSTALL_ID,
      credentials,
      root,
      prefix: "/usr",
      shell: "/bin/bash",
      uid: process.getuid?.() ?? -1,
      environment: { PATH: process.env.PATH ?? "/usr/bin:/bin" },
      npm: "/bin/false",
      pi: { command: process.execPath, args: [FAKE_PI, "--offline"] },
      piVersion: PI_VERSION,
      idleExitMs: 60_000,
      idlePollMs: 60_000,
      trackMs: 2000,
    };
    daemon = new Daemon(config);
    await daemon.start();
  });

  afterEach(async () => {
    await daemon.shutdown(0);
    await fsp.rm(root, { recursive: true, force: true });
  });

  it("accepts a trusted loopback certificate and bearer token over the required subprotocol", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    expect(client.socket.protocol).toBe("pipkin.rpc.v1");
    const response = await client.request("status");
    expect(response).toMatchObject({ jsonrpc: "2.0", result: { instance: daemon.instance, root, generations: [], receipts: [] } });
    expect(response.error).toBeUndefined();
  });

  it("rejects a server certificate outside the client's trust store", async () => {
    const unrelated = await testCredentials();
    await expect(TestClient.connect(daemon.port, credentials, { ca: unrelated.certificate })).rejects.toMatchObject({
      code: "DEPTH_ZERO_SELF_SIGNED_CERT",
    });
  });

  it.each<{ name: string; options: ConnectOptions; status: number }>([
    { name: "missing token", options: { token: null }, status: 401 },
    { name: "wrong token", options: { token: "0".repeat(64) }, status: 401 },
    { name: "wrong path", options: { pathname: "/not-rpc" }, status: 404 },
    { name: "missing subprotocol", options: { protocols: [] }, status: 400 },
    { name: "wrong subprotocol", options: { protocols: ["not-pipkin"] }, status: 400 },
    { name: "browser Origin", options: { origin: "https://localhost" }, status: 403 },
  ])("rejects $name at HTTP upgrade without redirecting or replacing the active client", async ({ options, status }) => {
    const active = await TestClient.connect(daemon.port, credentials);
    const failure: unknown = await TestClient.connect(daemon.port, credentials, options).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(UpgradeRejection);
    if (!(failure instanceof UpgradeRejection)) throw new Error("Expected an HTTP upgrade rejection");
    expect(failure.status).toBe(status);
    expect(failure.headers.location).toBeUndefined();
    expect((await active.result("status")).instance).toBe(daemon.instance);
    expect(active.closed).toBe(false);
  });

  it.each([
    { name: "malformed JSON", payload: "{", code: -32700 },
    { name: "non-object RPC", payload: "null", code: -32600 },
    { name: "wrong RPC version", payload: '{"jsonrpc":"1.0","method":"status"}', code: -32600 },
    { name: "object request ID", payload: '{"jsonrpc":"2.0","id":{},"method":"status"}', code: -32600 },
    { name: "non-string method", payload: '{"jsonrpc":"2.0","method":42}', code: -32600 },
    { name: "scalar parameters", payload: '{"jsonrpc":"2.0","method":"status","params":42}', code: -32600 },
    { name: "empty batch", payload: "[]", code: -32600 },
  ])("returns the standard error for $name and keeps the transport usable", async ({ payload, code }) => {
    const client = await TestClient.connect(daemon.port, credentials);
    client.socket.send(payload);
    const response = await client.next();
    expect(response).toMatchObject({ jsonrpc: "2.0", id: null, error: { code } });
    expect(response.result).toBeUndefined();
    expect((await client.result("status")).receipts).toEqual([]);
  });

  it("correlates unknown methods and invalid parameters with standard JSON-RPC errors", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    client.send({ jsonrpc: "2.0", id: 42, method: "does.not.exist", params: {} });
    expect(await client.until((record) => record.id === 42)).toMatchObject({ jsonrpc: "2.0", id: 42, error: { code: -32601 } });
    client.send({ jsonrpc: "2.0", id: "invalid-params", method: "agent.start", params: [1, "default"] });
    const invalid = await client.until((record) => record.id === "invalid-params");
    expect(invalid).toMatchObject({ jsonrpc: "2.0", id: "invalid-params", error: { code: -32602 } });
    expect(invalid.result).toBeUndefined();
    expect((await client.result("status")).generations).toEqual([]);
  });

  it("preserves null, zero and empty-string request IDs rather than treating them as notifications", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    client.send([
      { jsonrpc: "2.0", id: null, method: "status" },
      { jsonrpc: "2.0", id: 0, method: "status" },
      { jsonrpc: "2.0", id: "", method: "status" },
    ]);
    const responses = await client.nextBatch();
    expect(responses.map(record => record.id)).toEqual([null, 0, ""]);
    for (const response of responses) expect(response).toMatchObject({ result: { instance: daemon.instance } });
  });

  it("returns one batch response containing errors for invalid members and a result for a valid request", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    client.send([
      null,
      { jsonrpc: "2.0", id: "unknown", method: "does.not.exist", params: {} },
      { jsonrpc: "2.0", id: "status", method: "status", params: {} },
    ]);
    const response = await client.nextBatch();
    expect(response).toHaveLength(3);
    expect(response.find(record => record.id === null)).toMatchObject({ error: { code: -32600 } });
    expect(response.find(record => record.id === "unknown")).toMatchObject({ error: { code: -32601 } });
    expect(response.find(record => record.id === "status")).toMatchObject({ result: { instance: daemon.instance, receipts: [] } });
    expect((await client.result("status")).generations).toEqual([]);
  });

  it("keeps a singleton batch response as an array and does not answer notifications", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    client.send([
      { jsonrpc: "2.0", method: "status", params: {} },
      { jsonrpc: "2.0", id: "only-response", method: "status", params: {} },
    ]);
    expect(await client.nextBatch()).toMatchObject([
      { jsonrpc: "2.0", id: "only-response", result: { instance: daemon.instance } },
    ]);
  });

  it("refuses a second daemon on the same port with the port-in-use exit code", async () => {
    // A separate installation avoids confusing a live installation lock with a port conflict.
    const second = new Daemon({ ...config, root: path.join(root, "second"), port: daemon.port });
    try {
      const failure: unknown = await second.start().catch((error: unknown) => error);
      expect(failure).toBeInstanceOf(StartupFailure);
      expect(failure).toMatchObject({ code: EXIT_PORT_IN_USE });
    } finally {
      await second.shutdown(0);
    }
  });

  it("keeps Pi and receipts across reconnect, replays only missed events, and closes the generation and daemon", async () => {
    const first = await TestClient.connect(daemon.port, credentials);
    const idle = await first.result("status");
    expect(idle.piVersion).toBe(PI_VERSION);
    expect(idle.generations).toEqual([]);

    const started = await first.result("agent.start", { generation: 1, workspace: "default" });
    expect(started.piVersion).toBe(PI_VERSION);
    const pid = started.pid as number;
    expect(pid).toBeGreaterThan(1);
    const ready = await first.nextEvent("stderr");
    expect(ready).toMatchObject({ generation: 1, seq: 1, text: "fake-pi ready\n" });

    const command = { type: "prompt", message: "ping" };
    await first.result("agent.send", { generation: 1, record: command });
    const events = [await first.nextEvent("pi"), await first.nextEvent("pi"), await first.nextEvent("pi")];
    expect(events.map((event) => (event.record as JsonObject).type)).toEqual(["agent_start", "echo", "agent_settled"]);
    expect(events[1]?.record).toEqual({ type: "echo", command });
    expect(events.map((event) => event.seq)).toEqual([2, 3, 4]);

    const running = await first.result("status");
    expect(running.generations).toEqual([
      expect.objectContaining({ generation: 1, workspace: "default", pid, state: "running", lastSeq: 4, code: null, signal: null }),
    ]);
    const receipts = running.receipts as JsonObject[];
    expect(receipts.map((receipt) => [receipt.method, receipt.outcome])).toEqual([
      ["agent.start", "accepted"],
      ["agent.send", "accepted"],
    ]);

    first.socket.close();
    await first.closedPromise;
    const second = await TestClient.connect(daemon.port, credentials);
    const attached = await second.result("agent.attach", { generation: 1, after: 1 });
    expect(attached).toEqual({ lastSeq: 4, gap: false });
    const replayed = [await second.nextEvent("pi"), await second.nextEvent("pi"), await second.nextEvent("pi")];
    expect(replayed).toEqual(events);
    const reconnected = await second.result("status");
    expect(reconnected.generations).toEqual(running.generations);
    expect(reconnected.receipts).toEqual(receipts);
    expect(() => process.kill(pid, 0)).not.toThrow();

    await second.result("agent.close", { generation: 1 });
    expect(await second.nextEvent("exit")).toMatchObject({ generation: 1, code: 0, signal: null, seq: 5 });
    expect(() => process.kill(pid, 0)).toThrow();
    expect((await second.result("status")).generations).toEqual([expect.objectContaining({ generation: 1, state: "exited", code: 0 })]);
    await second.result("agent.close", { generation: 1 });

    const rejected = await second.request("agent.start", { generation: 1, workspace: "default" });
    expect(rejected).toMatchObject({ error: { code: -32000, data: { outcome: "rejected" } } });
    const badName = await second.request("agent.start", { generation: 2, workspace: "../escape" });
    expect(badName).toMatchObject({ error: { code: -32000, data: { outcome: "rejected" } } });
    expect(((await second.result("status")).receipts as JsonObject[]).map((receipt) => receipt.outcome)).toEqual([
      "accepted", "accepted", "accepted", "accepted", "rejected", "rejected",
    ]);

    expect(await second.result("shutdown")).toEqual({});
    await second.closedPromise;
    await daemon.shutdown(0);
    expect(daemon.exitStatus).toBe(0);
    await expect(fsp.access(path.join(root, ".daemon-lock"))).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("sequences pipelined sends after their generation starts", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    const start = client.result("agent.start", { generation: 1, workspace: "pipelined" });
    const first = client.result("agent.send", { generation: 1, record: { type: "prompt", message: "first" } });
    const second = client.result("agent.send", { generation: 1, record: { type: "prompt", message: "second" } });
    await Promise.all([start, first, second]);
    const commands: unknown[] = [];
    for (let index = 0; index < 6; index++) {
      const event = await client.nextEvent("pi");
      const record = event.record as JsonObject;
      if (record.type === "echo") commands.push(record.command);
    }
    expect(commands).toEqual([{ type: "prompt", message: "first" }, { type: "prompt", message: "second" }]);
    expect(((await client.result("status")).receipts as JsonObject[]).map((receipt) => [receipt.method, receipt.outcome])).toEqual([
      ["agent.start", "accepted"], ["agent.send", "accepted"], ["agent.send", "accepted"],
    ]);
  });

  it("reports an actual evicted journal range and replays the retained suffix", async () => {
    const first = await TestClient.connect(daemon.port, credentials);
    await first.result("agent.start", { generation: 5, workspace: "w" });
    const original = [await first.nextEvent("stderr")];
    // Six 1-MiB echoes exceed the real 4-MiB ring; every individual JSONL record stays below its limit.
    for (let index = 0; index < 6; index++) {
      await first.result("agent.send", { generation: 5, record: { type: "prompt", index, message: "x".repeat(1024 * 1024) } });
      original.push(await first.nextEvent("pi"), await first.nextEvent("pi"), await first.nextEvent("pi"));
    }
    first.socket.close();
    await first.closedPromise;
    const second = await TestClient.connect(daemon.port, credentials);
    expect(await second.result("agent.attach", { generation: 5, after: 0 })).toEqual({ lastSeq: 19, gap: true });
    const gap = await second.nextEvent("gap");
    expect(gap).toMatchObject({ generation: 5, from: 1 });
    const evictedThrough = gap.to as number;
    expect(evictedThrough).toBeGreaterThan(1);
    expect(evictedThrough).toBeLessThan(19);
    const replayed: JsonObject[] = [];
    for (let seq = evictedThrough + 1; seq <= 19; seq++) {
      const event = await second.nextEvent("pi");
      expect(event.seq).toBe(seq);
      replayed.push(event);
    }
    expect(replayed).toEqual(original.slice(evictedThrough));
    const unknown = await second.request("agent.attach", { generation: 6, after: 0 });
    expect(unknown).toMatchObject({ error: { code: -32000, data: { outcome: "rejected" } } });
  });

  it("revokes queued mutations from a superseded client before they can start Pi", async () => {
    await daemon.shutdown(0);
    await fsp.rm(manifest);
    const release = path.join(root, "release-install");
    const npm = path.join(root, "npm-gated.cjs");
    await fsp.writeFile(npm, `#!${process.execPath}\nconst fs = require("node:fs");\nconst watcher = fs.watch(${JSON.stringify(root)}, () => {\n  if (!fs.existsSync(${JSON.stringify(release)})) return;\n  watcher.close();\n  fs.writeFileSync(${JSON.stringify(manifest)}, ${JSON.stringify(JSON.stringify({ version: PI_VERSION }))});\n});\nprocess.stdout.write("install-blocked\\n");\n`, { mode: 0o700 });
    daemon = new Daemon({ ...config, npm });
    await daemon.start();
    const first = await TestClient.connect(daemon.port, credentials);
    first.send({ jsonrpc: "2.0", id: "install-in-flight", method: "runtime.ensure", params: {} });
    await first.nextEvent("progress");
    first.send({ jsonrpc: "2.0", id: "old-queued-start", method: "agent.start", params: { generation: 1, workspace: "revoked" } });
    // A pong proves the server consumed preceding frames, without waiting for its blocked work queue.
    const pong: PromiseWithResolvers<void> = Promise.withResolvers();
    first.socket.once("pong", () => { pong.resolve(); });
    first.socket.ping();
    await pong.promise;

    const second = await TestClient.connect(daemon.port, credentials);
    await first.nextEvent("superseded");
    await first.closedPromise;
    await fsp.writeFile(release, "release");
    const status = await second.result("status");
    expect(status.generations).toEqual([]);
    expect((status.receipts as JsonObject[]).filter((receipt) => receipt.id === "old-queued-start" && receipt.outcome === "accepted")).toEqual([]);
    await expect(fsp.access(path.join(root, "workspaces/revoked"))).rejects.toMatchObject({ code: "ENOENT" });
    const started = await second.result("agent.start", { generation: 1, workspace: "replacement" });
    expect((await second.result("status")).generations).toEqual([
      expect.objectContaining({ generation: 1, workspace: "replacement", pid: started.pid, state: "running" }),
    ]);
  });

  it("does not turn an uncertain npm failure into a definitive receipt or automatically retry it", async () => {
    await daemon.shutdown(0);
    await fsp.rm(manifest);
    const attempts = path.join(root, "install-attempts");
    const npm = path.join(root, "npm-failing.cjs");
    await fsp.writeFile(npm, `#!${process.execPath}\nrequire("node:fs").appendFileSync(${JSON.stringify(attempts)}, "attempt\\n");\nprocess.exit(1);\n`, { mode: 0o700 });
    daemon = new Daemon({ ...config, npm });
    await daemon.start();
    const first = await TestClient.connect(daemon.port, credentials);
    const failure = await first.request("runtime.ensure");
    expect(failure).toMatchObject({ jsonrpc: "2.0", error: { code: -32001, data: { outcome: "uncertain" } } });
    expect(failure.result).toBeUndefined();
    first.socket.close();
    await first.closedPromise;
    const second = await TestClient.connect(daemon.port, credentials);
    expect(await second.result("status")).toMatchObject({ piVersion: null, generations: [], receipts: [] });
    expect(await fsp.readFile(attempts, "utf8")).toBe("attempt\n");
  });
});
