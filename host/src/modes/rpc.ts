import { spawn } from "node:child_process";
import { readFile, stat } from "node:fs/promises";
import { join } from "node:path";
import { installedPiVersion, piPackageDir } from "../env.js";
import { isObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { nullableStringParam, stringParam } from "../params.js";
import { systemAppend } from "../prompt.js";

const STDERR_TAIL = 16 * 1024;
const TERM_AFTER_MS = 5000;
const KILL_AFTER_MS = 5000;

/** Keeps only the newest bytes of Pi's stderr for the exit report. */
class TailBuffer {
  private bytes: Buffer = Buffer.alloc(0);

  push(chunk: Buffer): void {
    const joined = Buffer.concat([this.bytes, chunk]);
    this.bytes = joined.length > STDERR_TAIL ? Buffer.from(joined.subarray(joined.length - STDERR_TAIL)) : joined;
  }

  text(): string {
    return this.bytes.toString("utf8");
  }
}

/**
 * One timestamped line on the host's stderr. Termux returns it to Pipkin once the host exits, which logs it, so a
 * dropped connection can be traced to its side.
 */
function note(message: string): void {
  process.stderr.write(`pipkin-host rpc ${new Date().toISOString()}: ${message}\n`);
}

async function piEntry(root: string): Promise<string> {
  const dir = piPackageDir(root);
  const manifest = JSON.parse(await readFile(join(dir, "package.json"), "utf8")) as unknown;
  const bin = isObject(manifest) ? manifest.bin : undefined;
  const entry = typeof bin === "string" ? bin : isObject(bin) ? bin.pi : undefined;
  if (typeof entry !== "string") throw new Error("Agent package.json has no bin.pi");
  return join(dir, entry);
}

/** Error results for rpc; success has no result line, only `pipkin_exit`. */
function fail(ctx: ModeContext, code: string, error: string): void {
  ctx.conn.send({ type: "pipkin_result", ok: false, error, code });
  ctx.conn.finish();
}

/**
 * Bridges the socket to `pi --mode rpc` byte-for-byte. Pi exits on stdin EOF, so closing the
 * socket stops the agent; signals only cover a Pi that ignores EOF.
 */
export async function rpc(ctx: ModeContext): Promise<void> {
  const cwd = stringParam(ctx.params, "cwd");
  const sessionPath = nullableStringParam(ctx.params, "sessionPath");
  const expectedVersion = stringParam(ctx.params, "expectedVersion");
  const installed = await installedPiVersion(ctx.env.root);
  if (installed !== expectedVersion) {
    const found = installed === null ? "The agent is not installed" : `Agent ${installed} is installed`;
    fail(ctx, "pi_version", `${found}; Pipkin needs ${expectedVersion}`);
    return;
  }
  const isDirectory = await stat(cwd).then(
    (stats) => stats.isDirectory(),
    () => false,
  );
  if (!isDirectory) {
    fail(ctx, "cwd_missing", `Folder not found: ${cwd}`);
    return;
  }

  const args = [await piEntry(ctx.env.root), "--mode", "rpc"];
  if (sessionPath !== null) args.push("--session", sessionPath);
  args.push("--append-system-prompt", systemAppend(ctx.env.configDir));
  const child = spawn(process.execPath, args, { cwd, stdio: ["pipe", "pipe", "pipe"] });
  const { socket } = ctx.conn;
  const stderr = new TailBuffer();
  child.stderr.on("data", (chunk: Buffer) => {
    stderr.push(chunk);
  });
  child.stdin.on("error", () => undefined);

  let reported = false;
  const report = (code: number | null, signal: string | null): void => {
    if (reported) return;
    reported = true;
    note(`pi exited: code=${code} signal=${signal} socketWritable=${socket.writable}`);
    ctx.conn.send({ type: "pipkin_exit", code, signal, stderr: stderr.text() });
    ctx.conn.finish();
  };
  // `close` fires only after stdout ended, so the exit line never interleaves with Pi output.
  child.on("close", report);
  child.on("error", (error) => {
    stderr.push(Buffer.from(`\n${error.message}\n`));
    report(null, null);
  });

  const { leftover } = ctx.conn;
  if (leftover.length > 0) child.stdin.write(leftover);
  socket.pipe(child.stdin);
  child.stdout.pipe(socket, { end: false });
  socket.on("end", () => {
    note("Pipkin ended the connection");
  });
  socket.on("error", (error) => {
    note(`socket error: ${error.message}`);
  });

  const stop = (): void => {
    note("stopping pi");
    if (!child.stdin.writableEnded) child.stdin.end();
    const running = (): boolean => child.exitCode === null && child.signalCode === null;
    setTimeout(() => {
      if (!running()) return;
      note("pi ignored stdin EOF; sending SIGTERM");
      child.kill("SIGTERM");
      setTimeout(() => {
        if (!running()) return;
        note("pi ignored SIGTERM; sending SIGKILL");
        child.kill("SIGKILL");
      }, KILL_AFTER_MS).unref();
    }, TERM_AFTER_MS).unref();
  };
  if (ctx.signal.aborted) stop();
  else ctx.signal.addEventListener("abort", stop, { once: true });
}
