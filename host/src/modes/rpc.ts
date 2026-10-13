import { spawn } from "node:child_process";
import { readFile, stat, writeFile } from "node:fs/promises";
import type { Socket } from "node:net";
import { join } from "node:path";
import type { Readable } from "node:stream";
import { installedPiVersion, loadPi, PI_PACKAGE, piPackageDir } from "../env.js";
import { isObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { nullableStringParam, stringParam } from "../params.js";
import { systemAppend } from "../prompt.js";
import { openSettingsIfValid } from "../settings.js";
import { termuxSessionEnv } from "../termux-session.js";

const STDERR_TAIL = 16 * 1024;
const TERM_AFTER_MS = 5000;
const KILL_AFTER_MS = 5000;
/**
 * Pi output the host holds while Pipkin is not reading, for example while the phone has frozen Pipkin with the screen
 * off. Below this Pi never waits on Pipkin; above it Pi waits until Pipkin reads again, so the host's memory stays
 * bounded.
 */
const OUTPUT_BACKLOG_LIMIT = 32 * 1024 * 1024;
const WEB_ACCESS_PACKAGE = "pi-web-access";

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

/**
 * Copies Pi's stdout to the socket like `pipe`, but lets up to `OUTPUT_BACKLOG_LIMIT` bytes queue in the socket
 * instead of pipe's 16 KiB, so Pi keeps working while Pipkin is not reading. Never ends the socket: the exit line
 * follows Pi's output. Once the socket is gone the output is dropped, so a Pi waiting to write gets going again and
 * sees its stdin close instead of waiting for SIGTERM.
 */
function forwardOutput(output: Readable, socket: Socket): void {
  output.on("data", (chunk: Buffer) => {
    if (!socket.writable) return;
    socket.write(chunk);
    if (socket.writableLength >= OUTPUT_BACKLOG_LIMIT) output.pause();
  });
  socket.on("drain", () => output.resume());
  socket.on("close", () => output.resume());
}

async function piEntry(root: string): Promise<string> {
  const dir = piPackageDir(root);
  const manifest = JSON.parse(await readFile(join(dir, "package.json"), "utf8")) as unknown;
  const bin = isObject(manifest) ? manifest.bin : undefined;
  const entry = typeof bin === "string" ? bin : isObject(bin) ? bin.pi : undefined;
  if (typeof entry !== "string") throw new Error("Agent package.json has no bin.pi");
  return join(dir, entry);
}

/** The user's global Pi settings as parsed JSON; null when the file is missing or malformed, which Pi reports itself. */
async function readGlobalSettings(configDir: string): Promise<unknown> {
  return readFile(join(configDir, "settings.json"), "utf8")
    .then((text) => JSON.parse(text.replace(/^\uFEFF/, "")) as unknown)
    .catch(() => null);
}

/**
 * npm package names the user's global Pi settings install. Pi parses `npm:<name>[@version]` sources with this same
 * pattern and identifies npm packages by name. Project settings are left out: Pi loads them only after a trust
 * decision that extensions, `trust.json` and prompts can make, which the host cannot predict.
 */
function userNpmPackages(settings: unknown): Set<string> {
  const names = new Set<string>();
  if (!isObject(settings) || !Array.isArray(settings.packages)) return names;
  for (const entry of settings.packages as unknown[]) {
    const source = isObject(entry) ? entry.source : entry;
    if (typeof source !== "string" || !source.startsWith("npm:")) continue;
    const spec = source.slice("npm:".length).trim();
    names.add(/^(@?[^@]+(?:\/[^@]+)?)(?:@.+)?$/.exec(spec)?.[1] ?? spec);
  }
  return names;
}

/** Pipkin's bundled Pi packages: every runtime dependency besides Pi. */
function bundledPackages(packageJson: string): string[] {
  const manifest = JSON.parse(packageJson) as unknown;
  const dependencies = isObject(manifest) && isObject(manifest.dependencies) ? Object.keys(manifest.dependencies) : [];
  return dependencies.filter((name) => name !== PI_PACKAGE);
}

/**
 * The bundled packages to load with `-e`, minus any the user installs through Pi's global settings. Pi exits when two
 * copies of one extension register the same tools, so the user's copy wins.
 */
function bundledExtensionDirs(ctx: ModeContext, packages: string[], settings: unknown): string[] {
  const userPackages = userNpmPackages(settings);
  return packages.filter((name) => !userPackages.has(name)).map((name) => join(ctx.env.root, "pi", "node_modules", name));
}

/**
 * Sets pi-web-access's `toolActivation` to `eager` in its `web-search.json` unless the user chose one, so every chat
 * has the web tools from its first request instead of behind a `web_enable` call some models only make sometimes.
 * A file that is not a JSON object is left alone; pi-web-access reports it itself.
 */
async function defaultWebToolsToEager(configDir: string): Promise<void> {
  const path = join(configDir, "web-search.json");
  let config: unknown;
  try {
    config = JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") return;
    config = {};
  }
  if (!isObject(config) || "toolActivation" in config) return;
  await writeFile(path, `${JSON.stringify({ ...config, toolActivation: "eager" }, null, 2)}\n`);
}

/**
 * Sets Pi's `steeringMode` to `all` unless the user chose one, so every steer sent during a turn reaches the model
 * together, as Pipkin's single steering box shows them. Writes through Pi's locked `SettingsManager`, since another
 * chat's Pi may be saving settings too; Pi itself reports settings it cannot read. A failed write only leaves Pi's
 * one-at-a-time default, which the steering box follows as well.
 */
async function defaultSteeringToAll(ctx: ModeContext, settings: unknown): Promise<void> {
  if (isObject(settings) && "steeringMode" in settings) return;
  const current = openSettingsIfValid(ctx, await loadPi(ctx.env.root));
  if (current === undefined || current.manager.getGlobalSettings().steeringMode !== undefined) return;
  current.manager.setSteeringMode("all");
  await current.manager.flush();
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
  const packageJson = stringParam(ctx.params, "packageJson");
  const installed = await installedPiVersion(ctx.env.root);
  if (installed !== expectedVersion) {
    const found = installed === null ? "The agent is not installed" : `Agent ${installed} is installed`;
    fail(ctx, "pi_version", `${found}; Pipkin needs ${expectedVersion}`);
    return;
  }
  // Bundled extensions can change while Pi's version stays put; ensure installs from this same manifest.
  const installedPackageJson = await readFile(join(ctx.env.root, "pi", "package.json"), "utf8").catch(() => null);
  if (installedPackageJson !== packageJson) {
    fail(ctx, "pi_version", "The agent's bundled extensions are out of date");
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
  const packages = bundledPackages(packageJson);
  if (packages.includes(WEB_ACCESS_PACKAGE)) await defaultWebToolsToEager(ctx.env.configDir);
  const settings = await readGlobalSettings(ctx.env.configDir);
  await defaultSteeringToAll(ctx, settings);
  for (const dir of bundledExtensionDirs(ctx, packages, settings)) args.push("--extension", dir);
  args.push("--append-system-prompt", systemAppend(ctx.env.configDir));
  const env = await termuxSessionEnv(ctx.env);
  const child = spawn(process.execPath, args, { cwd, env, stdio: ["pipe", "pipe", "pipe"] });
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
  forwardOutput(child.stdout, socket);
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
