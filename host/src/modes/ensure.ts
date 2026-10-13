import { spawn } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import type { Readable } from "node:stream";
import { HostError, installedPiVersion } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";

/** Longest progress line forwarded; npm never needs more, and the app shows only a few lines. */
const MAX_PROGRESS_LINE = 4096;

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

export async function ensure(ctx: ModeContext): Promise<JsonObject> {
  const packageJson = stringParam(ctx.params, "packageJson");
  const packageLock = stringParam(ctx.params, "packageLock");
  const expectedVersion = stringParam(ctx.params, "expectedVersion");
  const piDir = join(ctx.env.root, "pi");
  await mkdir(piDir, { recursive: true });
  await mkdir(ctx.env.configDir, { recursive: true });

  const packageJsonPath = join(piDir, "package.json");
  const lockPath = join(piDir, "package-lock.json");
  const [onDiskPackageJson, onDiskLock] = await Promise.all([
    readFile(packageJsonPath, "utf8").catch(() => null),
    readFile(lockPath, "utf8").catch(() => null),
  ]);
  // rpc refuses a package.json that differs from the app's, so both files must match to skip the install.
  const current =
    (await installedPiVersion(ctx.env.root)) === expectedVersion && onDiskPackageJson === packageJson && onDiskLock === packageLock;
  if (!current) {
    await writeFile(packageJsonPath, packageJson);
    await writeFile(lockPath, packageLock);
    await npmCi(ctx, piDir);
  }

  return { piVersion: await installedPiVersion(ctx.env.root) };
}
