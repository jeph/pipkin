/**
 * `node helper.mjs`: the daemon Pipkin starts its hosts through when Termux (the Google Play build) has no
 * RUN_COMMAND service. `run.sh` keeps it running; the exit codes below tell the wrapper what to do next.
 */
import { execFile, spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import {
  appendFileSync,
  closeSync,
  fstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { createServer, type Server, type Socket } from "node:net";
import { fileURLToPath } from "node:url";
import type { JsonObject } from "../framing.js";
import { HelperChannel } from "./channel.js";
import { HelperFiles, writeFileAtomic } from "./files.js";
import {
  ByteQueue,
  CLIENT_HELLO_BYTES,
  clientMac,
  MAC_BYTES,
  macEquals,
  parseKeyHex,
  PROTOCOL_VERSION,
  SERVER_MAGIC,
  serverMac,
  sessionKeys,
} from "./protocol.js";

/** Exit codes shared with `run.sh`. */
export const EXIT_RESTART = 75;
export const EXIT_PORTS_TAKEN = 76;
export const EXIT_NO_KEY = 78;

const DEFAULT_PORTS = [47310, 47311, 47312, 47313, 47314];
const HANDSHAKE_TIMEOUT_MS = 5000;
const WRAPPER_CHECK_MS = 1000;
const STDERR_TAIL = 4096;
const LOG_KEEP = 64 * 1024;
const TERMUX_PREFIX = "/data/data/com.termux/files/usr";
const PACKAGE_NAME = /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/;

/** `PIPKIN_HELPER_PORTS` (comma-separated) overrides the port list. Test-only: Pipkin always tries the defaults. */
function ports(): number[] {
  const override = process.env.PIPKIN_HELPER_PORTS;
  return override ? override.split(",").map((port) => Number.parseInt(port, 10)) : DEFAULT_PORTS;
}

export async function runDaemon(files = new HelperFiles()): Promise<number> {
  files.ensureDir();
  if (files.livePid() !== undefined) return 0;
  truncateLog(files.log);
  // Nobody reads these once their helper is gone; a child still writing keeps its unlinked file.
  rmSync(files.launches, { recursive: true, force: true });
  const log = (line: string): void => appendFileSync(files.log, `${new Date().toISOString()} ${line}\n`, { mode: 0o600 });
  let key: Buffer | undefined;
  try {
    key = parseKeyHex(readFileSync(files.key, "utf8"));
  } catch {
    key = undefined;
  }
  if (key === undefined) {
    log("no valid key");
    writeFileAtomic(files.status, "no-key\n");
    return EXIT_NO_KEY;
  }
  const version = createHash("sha256")
    .update(readFileSync(fileURLToPath(import.meta.url)))
    .digest("hex");
  const daemon = new Daemon(files, key, version, log);
  const port = await daemon.listen(ports());
  if (port === undefined) {
    log("all ports taken");
    writeFileAtomic(files.status, "ports-taken\n");
    return EXIT_PORTS_TAKEN;
  }
  // Handle signals before the pid and port files tell anyone this helper is up.
  const { promise, resolve } = Promise.withResolvers<number>();
  daemon.onStop = resolve;
  process.on("SIGTERM", () => daemon.stop(0, "SIGTERM"));
  process.on("SIGUSR1", () => daemon.stop(EXIT_RESTART, "SIGUSR1"));
  daemon.watchWrapper();
  writeFileAtomic(files.port, `${port}\n`);
  writeFileAtomic(files.pid, `${process.pid}\n`);
  rmSync(files.status, { force: true });
  log(`listening on ${port}, pid ${process.pid}, version ${version}`);
  return promise;
}

/** Keeps the last 64 KiB of `helper.log`, starting at a line boundary. */
function truncateLog(file: string): void {
  let size: number;
  try {
    size = statSync(file).size;
  } catch {
    return;
  }
  if (size <= LOG_KEEP) return;
  const tail = readFileSync(file).subarray(size - LOG_KEEP);
  writeFileSync(file, tail.subarray(tail.indexOf(0x0a) + 1), { mode: 0o600 });
}

/** The last 4096 UTF-16 units of a launch's stderr file, reading only as many bytes as that can take. */
function readTail(file: string): string {
  // 4 bytes per character at most, plus up to 3 bytes of a character cut at the start.
  const maxBytes = 4 * STDERR_TAIL + 3;
  let fd: number;
  try {
    fd = openSync(file, "r");
  } catch {
    return "";
  }
  try {
    const size = fstatSync(fd).size;
    const length = Math.min(size, maxBytes);
    const bytes = Buffer.alloc(length);
    readSync(fd, bytes, 0, length, size - length);
    return bytes.toString("utf8").slice(-STDERR_TAIL);
  } finally {
    closeSync(fd);
  }
}

class Daemon {
  private server: Server | undefined;
  private readonly sockets = new Set<Socket>();
  private readonly wrapperPid = process.ppid;
  private wrapperTimer: NodeJS.Timeout | undefined;
  onStop: (code: number) => void = () => undefined;

  constructor(
    private readonly files: HelperFiles,
    private readonly key: Buffer,
    private readonly version: string,
    private readonly log: (line: string) => void,
  ) {}

  /** Binds the first free port on 127.0.0.1; undefined when every port is taken. */
  async listen(candidates: number[]): Promise<number | undefined> {
    for (const port of candidates) {
      const server = createServer((socket) => this.accept(socket));
      const { promise, resolve } = Promise.withResolvers<boolean>();
      server.once("error", () => resolve(false));
      server.listen(port, "127.0.0.1", () => resolve(true));
      if (await promise) {
        this.server = server;
        return port;
      }
    }
    return undefined;
  }

  /**
   * Termux's Exit kills `run.sh` but not this helper, which then neither restarts nor keeps Termux's service up, so
   * Android freezes it. Such a helper stops, checked every second and on every connection (a frozen one thaws when
   * Termux opens), so the fresh `run.sh` that opening Termux starts takes over and no launch lands on a doomed helper.
   */
  watchWrapper(): void {
    this.wrapperTimer = setInterval(() => this.stopIfOrphaned(), WRAPPER_CHECK_MS);
    this.wrapperTimer.unref();
  }

  /** Whether this helper lost the process that started it, in which case it stops. */
  private stopIfOrphaned(): boolean {
    if (process.ppid === this.wrapperPid) return false;
    this.stop(0, "wrapper gone");
    return true;
  }

  /** Stops listening and removes the pid and port files; started children keep running. */
  stop(code: number, reason: string): void {
    if (this.server === undefined) return;
    clearInterval(this.wrapperTimer);
    this.server.close();
    this.server = undefined;
    for (const socket of this.sockets) socket.destroy();
    let pidInFile = "";
    try {
      pidInFile = readFileSync(this.files.pid, "utf8").trim();
    } catch {
      // Nothing to remove.
    }
    if (pidInFile === String(process.pid)) {
      rmSync(this.files.pid, { force: true });
      rmSync(this.files.port, { force: true });
    }
    this.log(`stopping (${reason}), exit ${code}`);
    this.onStop(code);
  }

  private accept(socket: Socket): void {
    if (this.stopIfOrphaned()) {
      socket.destroy();
      return;
    }
    this.sockets.add(socket);
    socket.on("close", () => this.sockets.delete(socket));
    socket.on("error", () => undefined);
    socket.setNoDelay(true);
    const timer = setTimeout(() => {
      this.log("handshake timed out");
      socket.destroy();
    }, HANDSHAKE_TIMEOUT_MS);
    socket.on("close", () => clearTimeout(timer));
    const queue = new ByteQueue();
    let nonceC: Buffer | undefined;
    let nonceS: Buffer | undefined;
    socket.on("data", (chunk: Buffer) => {
      queue.push(chunk);
      if (nonceC === undefined || nonceS === undefined) {
        const hello = queue.take(CLIENT_HELLO_BYTES);
        if (hello === undefined) return;
        if (hello[0] !== PROTOCOL_VERSION) {
          this.log(`unsupported protocol version ${String(hello[0])}`);
          socket.destroy();
          return;
        }
        nonceC = hello.subarray(1);
        nonceS = randomBytes(32);
        socket.write(Buffer.concat([SERVER_MAGIC, nonceS, serverMac(this.key, nonceC, nonceS)]));
      }
      const mac = queue.take(MAC_BYTES);
      if (mac === undefined) return;
      if (!macEquals(mac, clientMac(this.key, nonceC, nonceS))) {
        this.log("client failed authentication");
        socket.destroy();
        return;
      }
      clearTimeout(timer);
      const { c2s, s2c } = sessionKeys(this.key, nonceC, nonceS);
      const channel = new HelperChannel(socket, s2c, c2s, (message) => this.handle(channel, message));
      channel.receive(queue.rest());
    });
  }

  private handle(channel: HelperChannel, message: JsonObject): void {
    switch (message.type) {
      case "hello":
        channel.send({ type: "hello", version: this.version, pid: process.pid });
        return;
      case "launch":
        this.launch(channel, message);
        return;
      case "update":
        this.update(channel, message);
        return;
      case "return":
        this.bringToFront(channel, message);
        return;
      default:
        channel.send({ type: "error", message: "unknown request" });
    }
  }

  private launch(channel: HelperChannel, message: JsonObject): void {
    const { args, stdin } = message;
    if (!Array.isArray(args) || !args.every((arg) => typeof arg === "string") || typeof stdin !== "string") {
      channel.send({ type: "error", message: "bad launch request" });
      return;
    }
    // stderr goes to a file, not a pipe: a pipe would break (SIGPIPE, EPIPE) for children that outlive this helper.
    const stderrFile = path.join(this.files.launches, `${randomBytes(8).toString("hex")}.err`);
    let stderrFd: number;
    try {
      mkdirSync(this.files.launches, { recursive: true, mode: 0o700 });
      stderrFd = openSync(stderrFile, "a", 0o600);
    } catch (error) {
      channel.send({ type: "error", message: error instanceof Error ? error.message : String(error) });
      return;
    }
    const bash = `${process.env.PREFIX ?? TERMUX_PREFIX}/bin/bash`;
    const child = spawn(bash, args, {
      cwd: process.env.HOME ?? this.files.dir,
      detached: true,
      stdio: ["pipe", "ignore", stderrFd],
    });
    closeSync(stderrFd);
    child.unref();
    child.stdin?.on("error", () => undefined);
    child.once("error", (error) => {
      if (child.pid !== undefined) return;
      rmSync(stderrFile, { force: true });
      this.log(`launch failed: ${error.message}`);
      channel.send({ type: "error", message: error.message });
    });
    child.once("spawn", () => {
      this.log(`launched pid ${String(child.pid)}`);
      channel.send({ type: "started", pid: child.pid });
      child.stdin?.end(stdin);
    });
    child.once("exit", (code, signal) => {
      this.log(`pid ${String(child.pid)} exited (${String(code ?? signal)})`);
      const stderr = readTail(stderrFile);
      rmSync(stderrFile, { force: true });
      channel.send({ type: "exit", code, signal, stderr });
    });
  }

  private update(channel: HelperChannel, message: JsonObject): void {
    const { source, sha256 } = message;
    if (typeof source !== "string" || typeof sha256 !== "string") {
      channel.send({ type: "error", message: "bad update request" });
      return;
    }
    if (createHash("sha256").update(source, "utf8").digest("hex") !== sha256.toLowerCase()) {
      channel.send({ type: "error", message: "sha256 mismatch" });
      return;
    }
    try {
      writeFileAtomic(this.files.helper, source);
    } catch (error) {
      channel.send({ type: "error", message: error instanceof Error ? error.message : String(error) });
      return;
    }
    this.log(`updated to ${sha256}`);
    const restart = (): void => this.stop(EXIT_RESTART, "update");
    if (channel.open) channel.send({ type: "updated" }, restart);
    else restart();
  }

  private bringToFront(channel: HelperChannel, message: JsonObject): void {
    const pkg = message.package;
    if (typeof pkg !== "string" || !PACKAGE_NAME.test(pkg)) {
      channel.send({ type: "returned", ok: false });
      return;
    }
    const component = `${pkg}/io.jeph.pipkin.MainActivity`;
    execFile(
      "am",
      ["start", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component],
      { timeout: 10_000 },
      (error) => channel.send({ type: "returned", ok: error === null }),
    );
  }
}
