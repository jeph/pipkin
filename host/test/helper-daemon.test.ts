import { type ChildProcess, spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { once } from "node:events";
import { copyFile, mkdir, mkdtemp, readdir, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import { createServer as createHttpServer } from "node:http";
import { connect, createServer, type AddressInfo, type Server, type Socket } from "node:net";
import os from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { afterEach, beforeEach, describe, expect, inject, it } from "vitest";
import { connectHelper, HelperForeignError, HelperKeyMismatchError } from "../src/helper/client.js";
import {
  clientMac,
  FrameOpener,
  FrameSealer,
  PROTOCOL_VERSION,
  SERVER_HELLO_BYTES,
  sessionKeys,
} from "../src/helper/protocol.js";

const bundle = path.join(path.dirname(inject("hostBundle")), "pipkin-helper.mjs");
const KEY = randomBytes(32);

interface Env {
  root: string;
  home: string;
  dir: string;
  amLog: string;
  /** The two ports in the helper's `ports` file. */
  ports: number[];
  env: NodeJS.ProcessEnv;
}

let env: Env;
const children: ChildProcess[] = [];
const servers: Server[] = [];

beforeEach(async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "pipkin-helper-"));
  const prefix = path.join(root, "usr");
  const home = path.join(root, "home");
  const dir = path.join(home, ".pipkin", "helper");
  const amLog = path.join(root, "am.log");
  await mkdir(path.join(prefix, "bin"), { recursive: true });
  await mkdir(dir, { recursive: true, mode: 0o700 });
  await symlink("/bin/bash", path.join(prefix, "bin", "bash"));
  await writeFile(path.join(prefix, "bin", "am"), `#!/bin/sh\necho "$*" >> '${amLog}'\n`, { mode: 0o755 });
  await copyFile(bundle, path.join(dir, "helper.mjs"));
  await writeFile(path.join(dir, "key"), `${KEY.toString("hex")}\n`, { mode: 0o600 });
  const ports = await freePorts(2);
  await writeFile(path.join(dir, "ports"), `${ports.join(",")}\n`);
  env = {
    root,
    home,
    dir,
    amLog,
    ports,
    env: {
      PATH: `${path.join(prefix, "bin")}:${process.env.PATH ?? ""}`,
      PREFIX: prefix,
      HOME: home,
    },
  };
});

afterEach(async () => {
  for (const child of children.splice(0)) if (child.exitCode === null) child.kill("SIGKILL");
  for (const server of servers.splice(0)) server.close();
  await rm(env.root, { recursive: true, force: true });
});

async function freePorts(count: number): Promise<number[]> {
  const held = await Promise.all(Array.from({ length: count }, () => listen(createServer(), 0)));
  const ports = held.map((server) => (server.address() as AddressInfo).port);
  await Promise.all(held.map((server) => new Promise((resolve) => server.close(resolve))));
  return ports;
}

async function listen(server: Server, port: number): Promise<Server> {
  server.listen(port, "127.0.0.1");
  await once(server, "listening");
  servers.push(server);
  return server;
}

function file(name: string): string {
  return path.join(env.dir, name);
}

async function waitFor<T>(probe: () => Promise<T | undefined>, timeoutMs = 10_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await probe();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error("Timed out");
    await sleep(25);
  }
}

const readOptional = (name: string): Promise<string | undefined> =>
  readFile(name, "utf8").then(
    (text) => text,
    () => undefined,
  );

function runHelper(args: string[] = [], extraEnv: NodeJS.ProcessEnv = {}): ChildProcess {
  const child = spawn(process.execPath, [file("helper.mjs"), ...args], {
    env: { ...env.env, ...extraEnv },
    stdio: ["ignore", "pipe", "pipe"],
  });
  children.push(child);
  return child;
}

async function exitCode(child: ChildProcess): Promise<number | null> {
  if (child.exitCode !== null) return child.exitCode;
  const [code] = (await once(child, "exit")) as [number | null];
  return code;
}

/** Starts the daemon and resolves to the port it listens on. */
async function startDaemon(): Promise<{ child: ChildProcess; port: number }> {
  const child = runHelper();
  const port = await waitFor(async () => {
    if (child.exitCode !== null) throw new Error(`Helper exited with ${child.exitCode}`);
    const text = await readOptional(file("helper.port"));
    return text === undefined ? undefined : Number(text.trim());
  });
  return { child, port };
}

/** A bare client that speaks the handshake itself, so tests can send frames a real client never would. */
async function rawSession(port: number, key: Buffer, macOverride?: Buffer) {
  const socket = connect({ port, host: "127.0.0.1" });
  await once(socket, "connect");
  const nonceC = randomBytes(32);
  socket.write(Buffer.concat([Buffer.of(PROTOCOL_VERSION), nonceC]));
  let received = Buffer.alloc(0);
  // `once` would reject on the ECONNRESET the helper's hang-up may cause; only the close matters here.
  const { promise: closed, resolve: onClose } = Promise.withResolvers<void>();
  socket.on("close", () => onClose());
  socket.on("error", () => undefined);
  socket.on("data", (chunk: Buffer) => {
    received = Buffer.concat([received, chunk]);
  });
  await waitFor(async () => (received.length >= SERVER_HELLO_BYTES ? true : undefined));
  const nonceS = received.subarray(4, 36);
  received = received.subarray(SERVER_HELLO_BYTES);
  socket.write(macOverride ?? clientMac(key, nonceC, nonceS));
  const { c2s, s2c } = sessionKeys(key, nonceC, nonceS);
  return {
    socket,
    closed,
    sealer: new FrameSealer(c2s),
    opener: new FrameOpener(s2c),
    received: () => received,
  };
}

const marker = (name: string): string[] => ["-c", `echo ran >> "${path.join(env.root, name)}"`];

describe("helper daemon", () => {
  it("serves hello, launch with stdin and stderr tail, return and unknown requests", async () => {
    const { child, port } = await startDaemon();
    expect(await readOptional(file("helper.pid"))).toBe(`${child.pid}\n`);
    const connection = await connectHelper({ port, keys: [randomBytes(32), KEY] });
    expect(connection.keyIndex).toBe(1);

    connection.send({ type: "hello" });
    const version = createHash("sha256")
      .update(await readFile(file("helper.mjs")))
      .digest("hex");
    expect(await connection.next()).toEqual({ type: "hello", version, pid: child.pid });

    const script = 'read -r line; echo "$line" > "$HOME/out.txt"; echo "out:$line"; printf "%5000s" x >&2; echo "err:$line" >&2; exit 3';
    connection.send({ type: "launch", args: ["--noprofile", "--norc", "-c", script], stdin: "hello there\n" });
    const started = await connection.next();
    expect(started).toMatchObject({ type: "started" });
    expect(typeof started.pid).toBe("number");
    const exit = await connection.next();
    expect(exit).toMatchObject({ type: "exit", code: 3, signal: null });
    expect(exit.stderr).toHaveLength(4096);
    expect(String(exit.stderr).endsWith(" xerr:hello there\n")).toBe(true);
    expect(await readFile(path.join(env.home, "out.txt"), "utf8")).toBe("hello there\n");
    expect(await readdir(file("launches"))).toEqual([]);

    connection.send({ type: "return", package: "io.jeph.pipkin.debug" });
    expect(await connection.next()).toEqual({ type: "returned", ok: true });
    expect(await readFile(env.amLog, "utf8")).toBe(
      "start --user 0 --activity-reorder-to-front -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n io.jeph.pipkin.debug/io.jeph.pipkin.MainActivity\n",
    );
    connection.send({ type: "return", package: "io.jeph.pipkin; rm -rf /" });
    expect(await connection.next()).toEqual({ type: "returned", ok: false });

    connection.send({ type: "launch", args: "nope", stdin: "" });
    expect(await connection.next()).toMatchObject({ type: "error" });
    connection.send({ type: "dance" });
    expect(await connection.next()).toEqual({ type: "error", message: "unknown request" });
    connection.close();
  });

  it("keeps a child that writes to stderr alive across a helper restart", async () => {
    const { child, port } = await startDaemon();
    const connection = await connectHelper({ port, keys: [KEY] });
    const result = path.join(env.root, "result");
    const script = `for i in $(seq 20); do echo tick >&2; sleep 0.05; done; echo "ok:$?" > "${result}"`;
    connection.send({ type: "launch", args: ["-c", script], stdin: "" });
    expect(await connection.next()).toMatchObject({ type: "started" });
    child.kill("SIGUSR1");
    expect(await exitCode(child)).toBe(75);
    expect(await waitFor(() => readOptional(result))).toBe("ok:0\n");

    // The restarted helper clears stderr files no helper is left to read.
    expect(await readdir(file("launches"))).toHaveLength(1);
    await startDaemon();
    expect(await readdir(file("launches")).catch(() => [])).toEqual([]);
  });

  it("keeps a child running after its connection closes", async () => {
    const { port } = await startDaemon();
    const connection = await connectHelper({ port, keys: [KEY] });
    const done = path.join(env.root, "done");
    connection.send({ type: "launch", args: ["-c", `sleep 0.5; echo done > "${done}"`], stdin: "" });
    expect(await connection.next()).toMatchObject({ type: "started" });
    connection.close();
    expect(await waitFor(() => readOptional(done))).toBe("done\n");
  });

  it("reports a spawn failure", async () => {
    await rm(path.join(String(env.env.PREFIX), "bin", "bash"));
    const { port } = await startDaemon();
    const connection = await connectHelper({ port, keys: [KEY] });
    connection.send({ type: "launch", args: ["-c", "true"], stdin: "" });
    expect(await connection.next()).toMatchObject({ type: "error" });
    connection.close();
  });

  it("rejects a wrong key before processing any request", async () => {
    const { port } = await startDaemon();
    await expect(connectHelper({ port, keys: [randomBytes(32)] })).rejects.toThrow(HelperKeyMismatchError);

    // A client that guesses its MAC and sends a launch right behind it is cut off without the launch running.
    const wrong = randomBytes(32);
    const session = await rawSession(port, wrong, randomBytes(32));
    session.socket.write(session.sealer.seal({ type: "launch", args: marker("wrong-key"), stdin: "" }));
    await session.closed;
    expect(session.received()).toHaveLength(0);
    await sleep(300);
    expect(await readOptional(path.join(env.root, "wrong-key"))).toBeUndefined();
  });

  it("closes the connection on a tampered, replayed or reordered frame without running anything", async () => {
    const { port } = await startDaemon();

    const tampered = await rawSession(port, KEY);
    const frame = tampered.sealer.seal({ type: "launch", args: marker("tampered"), stdin: "" });
    frame[10] = (frame[10] ?? 0) ^ 0x01;
    tampered.socket.write(frame);
    await tampered.closed;

    const reordered = await rawSession(port, KEY);
    reordered.sealer.seal({ type: "hello" });
    reordered.socket.write(reordered.sealer.seal({ type: "launch", args: marker("reordered"), stdin: "" }));
    await reordered.closed;

    const replayed = await rawSession(port, KEY);
    const launch = replayed.sealer.seal({ type: "launch", args: marker("replayed"), stdin: "" });
    replayed.socket.write(launch);
    await waitFor(() => readOptional(path.join(env.root, "replayed")));
    replayed.socket.write(launch);
    await replayed.closed;
    expect(replayed.opener.push(replayed.received())[0]).toMatchObject({ type: "started" });

    await sleep(300);
    expect(await readOptional(path.join(env.root, "tampered"))).toBeUndefined();
    expect(await readOptional(path.join(env.root, "reordered"))).toBeUndefined();
    expect(await readOptional(path.join(env.root, "replayed"))).toBe("ran\n");
  });

  it("works through a byte relay that only sees ciphertext", async () => {
    const { port } = await startDaemon();
    const relayed: Buffer[] = [];
    const relay = await listen(
      createServer((inbound: Socket) => {
        const outbound = connect({ port, host: "127.0.0.1" });
        inbound.on("data", (chunk: Buffer) => {
          relayed.push(chunk);
          outbound.write(chunk);
        });
        outbound.on("data", (chunk: Buffer) => {
          relayed.push(chunk);
          inbound.write(chunk);
        });
        inbound.on("close", () => outbound.destroy());
        outbound.on("close", () => inbound.destroy());
      }),
      0,
    );
    const connection = await connectHelper({ port: (relay.address() as AddressInfo).port, keys: [KEY] });
    connection.send({ type: "launch", args: ["-c", "echo secret-output >&2"], stdin: "secret-input" });
    expect(await connection.next()).toMatchObject({ type: "started" });
    expect(await connection.next()).toMatchObject({ type: "exit", code: 0, stderr: "secret-output\n" });
    connection.close();
    const seen = Buffer.concat(relayed).toString("latin1");
    for (const plaintext of ["type", "launch", "secret", "started"]) expect(seen).not.toContain(plaintext);
  });

  it("tells a foreign service on the port from a helper", async () => {
    const talker = await listen(
      createServer((socket) => socket.end("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")),
      0,
    );
    await expect(
      connectHelper({ port: (talker.address() as AddressInfo).port, keys: [KEY] }),
    ).rejects.toThrow(HelperForeignError);

    const silent = await listen(createServer(), 0);
    await expect(
      connectHelper({ port: (silent.address() as AddressInfo).port, keys: [KEY], helloTimeoutMs: 300 }),
    ).rejects.toThrow(HelperForeignError);
  });

  it("runs as a single instance", async () => {
    const { child, port } = await startDaemon();
    expect(await exitCode(runHelper())).toBe(0);
    expect(await readOptional(file("helper.pid"))).toBe(`${child.pid}\n`);
    expect(await readOptional(file("helper.port"))).toBe(`${port}\n`);
  });

  it("listens on its ports file's first free port and reports when all are taken", async () => {
    const [first, second] = env.ports;
    await listen(createServer(), Number(first));
    const { child, port } = await startDaemon();
    expect(port).toBe(second);
    child.kill("SIGTERM");
    expect(await exitCode(child)).toBe(0);
    expect(await readOptional(file("helper.pid"))).toBeUndefined();
    expect(await readOptional(file("helper.port"))).toBeUndefined();

    await listen(createServer(), Number(second));
    expect(await exitCode(runHelper())).toBe(76);
    expect(await readOptional(file("status"))).toBe("ports-taken\n");
  });

  it("exits 75 on SIGUSR1 and 78 without a key", async () => {
    const { child } = await startDaemon();
    child.kill("SIGUSR1");
    expect(await exitCode(child)).toBe(75);

    await writeFile(file("key"), "not a key\n");
    expect(await exitCode(runHelper())).toBe(78);
    expect(await readOptional(file("status"))).toBe("no-key\n");
  });

  it("stops once the process that started it is gone", async () => {
    const wrapper = spawn("/bin/sh", ["-c", `"$0" "$1" & exec sleep 30`, process.execPath, file("helper.mjs")], {
      env: env.env,
      stdio: "ignore",
    });
    children.push(wrapper);
    const pid = await waitFor(async () => (await readOptional(file("helper.pid")))?.trim() || undefined);
    const alive = (): boolean => {
      try {
        process.kill(Number(pid), 0);
        return true;
      } catch {
        return false;
      }
    };
    try {
      wrapper.kill("SIGKILL");
      await waitFor(async () => (alive() ? undefined : true), 5000);
      expect(await readOptional(file("helper.pid"))).toBeUndefined();
      expect(await readOptional(file("helper.log"))).toContain("stopping (wrapper gone), exit 0");
    } finally {
      if (alive()) process.kill(Number(pid), "SIGKILL");
    }
  });

  it("replaces helper.mjs on update and exits 75", async () => {
    const { child, port } = await startDaemon();
    const connection = await connectHelper({ port, keys: [KEY] });
    const source = "console.log('new helper');\n";
    connection.send({ type: "update", source, sha256: "0".repeat(64) });
    expect(await connection.next()).toEqual({ type: "error", message: "sha256 mismatch" });
    connection.send({ type: "update", source, sha256: createHash("sha256").update(source).digest("hex") });
    expect(await connection.next()).toEqual({ type: "updated" });
    expect(await exitCode(child)).toBe(75);
    expect(await readFile(file("helper.mjs"), "utf8")).toBe(source);
    expect((await stat(file("helper.mjs"))).mode & 0o777).toBe(0o600);
    expect((await readdir(env.dir)).filter((name) => name.endsWith(".tmp"))).toEqual([]);
  });
});

describe("helper pair", () => {
  const sid = "00112233445566778899aabbccddeeff";
  const token = "11".repeat(32);
  const body =
    "222222222222222222222222319d2a307af21c87b8dbc08698713d1eca98a06961f19086e5b493fa03650cce9202f9300b110c5b7284c10e82768b8e";
  const vectorKey = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

  async function pairWith(status: number): Promise<{ code: number | null; stderr: string }> {
    const server = createHttpServer((request, response) => {
      response.writeHead(request.url === `/pipkin/${sid}/key` ? status : 404, { "Cache-Control": "no-store" });
      response.end(status === 200 ? body : "");
    });
    server.listen(0, "127.0.0.1");
    await once(server, "listening");
    try {
      const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/pipkin/${sid}`;
      const child = runHelper(["pair"], { PIPKIN_URL: url, PIPKIN_TOKEN: token });
      let stderr = "";
      child.stderr?.on("data", (chunk: Buffer) => {
        stderr += chunk.toString();
      });
      const code = await exitCode(child);
      return { code, stderr };
    } finally {
      server.close();
    }
  }

  it("writes the key from Pipkin", async () => {
    await rm(file("key"));
    expect(await pairWith(200)).toEqual({ code: 0, stderr: "" });
    expect(await readFile(file("key"), "utf8")).toBe(`${vectorKey}\n`);
    expect((await stat(file("key"))).mode & 0o777).toBe(0o600);
  });

  it("returns 10 when the session is gone and leaves the key alone", async () => {
    expect((await pairWith(410)).code).toBe(10);
    expect(await readFile(file("key"), "utf8")).toBe(`${KEY.toString("hex")}\n`);
  });

  it("fails with a Pipkin message on other answers", async () => {
    const { code, stderr } = await pairWith(500);
    expect(code).toBe(1);
    expect(stderr).toMatch(/^Pipkin: .+\n$/);
  });
});
