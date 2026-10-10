import { spawn } from "node:child_process";
import { access, appendFile, cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import type { Readable } from "node:stream";
import { HostError, installedPiVersion } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";

/** Longest progress line forwarded; npm never needs more, and the app shows only a few lines. */
const MAX_PROGRESS_LINE = 4096;

async function exists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

function forwardLines(stream: Readable, send: (line: string) => void): void {
  let carry = "";
  stream.setEncoding("utf8");
  stream.on("data", (chunk: string) => {
    const lines = (carry + chunk).split("\n");
    carry = (lines.pop() ?? "").slice(-MAX_PROGRESS_LINE);
    for (const line of lines) send(line.replace(/\r$/, "").slice(0, MAX_PROGRESS_LINE));
  });
  stream.on("end", () => {
    if (carry.length > 0) send(carry);
  });
}

async function npmCi(ctx: ModeContext, cwd: string): Promise<void> {
  const send = (line: string): void => {
    ctx.conn.send({ type: "pipkin_progress", line });
  };
  const child = spawn("npm", ["ci", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error"], {
    cwd,
    signal: ctx.signal,
    stdio: ["ignore", "pipe", "pipe"],
  });
  forwardLines(child.stdout, send);
  forwardLines(child.stderr, send);
  const { promise, resolve, reject } = Promise.withResolvers<number | null>();
  child.on("error", reject);
  child.on("close", resolve);
  const code = await promise;
  if (code !== 0) throw new HostError("npm_failed", `npm ci failed (exit ${code ?? "signal"})`);
}

/** Copies a pre-Pipkin `~/.pi/agent` once, so existing terminal Pi config carries over. */
async function migrateLegacyConfig(ctx: ModeContext): Promise<void> {
  const { configDir, home } = ctx.env;
  for (const name of ["settings.json", "auth.json", "models.json"]) {
    if (await exists(join(configDir, name))) return;
  }
  const legacy = join(home, ".pi", "agent");
  if (!(await exists(legacy))) return;
  await cp(legacy, configDir, { recursive: true, force: false });
}

/** Lets terminal `pi` in Termux share the config Pipkin uses. */
async function exportConfigDir(ctx: ModeContext): Promise<void> {
  const bashrc = join(ctx.env.home, ".bashrc");
  const line = `export PI_CODING_AGENT_DIR="${ctx.env.configDir}"`;
  let current = "";
  try {
    current = await readFile(bashrc, "utf8");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  if (current.split("\n").includes(line)) return;
  const separator = current.length > 0 && !current.endsWith("\n") ? "\n" : "";
  await appendFile(bashrc, `${separator}${line}\n`);
}

export async function ensure(ctx: ModeContext): Promise<JsonObject> {
  const packageJson = stringParam(ctx.params, "packageJson");
  const packageLock = stringParam(ctx.params, "packageLock");
  const expectedVersion = stringParam(ctx.params, "expectedVersion");
  const piDir = join(ctx.env.root, "pi");
  await mkdir(piDir, { recursive: true });
  await mkdir(ctx.env.configDir, { recursive: true });

  const lockPath = join(piDir, "package-lock.json");
  const onDiskLock = await readFile(lockPath).catch(() => null);
  const current =
    (await installedPiVersion(ctx.env.root)) === expectedVersion &&
    onDiskLock !== null &&
    onDiskLock.equals(Buffer.from(packageLock, "utf8"));
  if (!current) {
    await writeFile(join(piDir, "package.json"), packageJson);
    await writeFile(lockPath, packageLock);
    await npmCi(ctx, piDir);
  }

  await migrateLegacyConfig(ctx);
  await exportConfigDir(ctx);
  return { piVersion: await installedPiVersion(ctx.env.root) };
}
