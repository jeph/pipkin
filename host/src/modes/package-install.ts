import { spawn } from "node:child_process";
import { configBusy, isConfigLocked } from "../config-lock.js";
import { HostError, piEntry } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { termuxSessionEnv } from "../termux-session.js";
import { commandFailed, forwardLines } from "./ensure.js";

/**
 * Tries `pi install` this many times. A failure is often transient (a dropped connection, or an npm cache entry whose
 * file is gone, which npm drops as it fails), and the next attempt then succeeds.
 */
const ATTEMPTS = 2;

/**
 * Runs `pi install <source>` in Pipkin's Pi, which adds the package to Pi's global settings. Each chat loads it the
 * next time its Pi starts. Pi's output arrives as progress lines; a failed attempt is retried once, and a second
 * failure reports Pi's last line.
 */
export async function packageInstall(ctx: ModeContext): Promise<JsonObject> {
  const source = stringParam(ctx.params, "source");
  // A restore swaps the configuration while it holds the lock, which would drop the settings Pi writes, and an
  // extension update runs npm in the same folder Pi installs into.
  if (await isConfigLocked(ctx.env)) throw configBusy();
  const args = [await piEntry(ctx.env.root), "install", source];
  const env = await termuxSessionEnv(ctx.env);
  let result = { code: null as number | null, lastLine: "" };
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    result = await runPi(ctx, args, env);
    if (result.code === 0) return {};
  }
  if (result.lastLine !== "") throw new HostError("install_failed", result.lastLine);
  throw commandFailed("install_failed", "pi install", result.code);
}

/** One run of Pi, forwarding its output as progress lines; returns its exit code and last non-blank line. */
async function runPi(ctx: ModeContext, args: string[], env: NodeJS.ProcessEnv): Promise<{ code: number | null; lastLine: string }> {
  let lastLine = "";
  const send = (line: string): void => {
    if (line.trim().length > 0) lastLine = line;
    ctx.conn.send({ type: "pipkin_progress", line });
  };
  const child = spawn(process.execPath, args, {
    cwd: ctx.env.home,
    env,
    signal: ctx.signal,
    stdio: ["ignore", "pipe", "pipe"],
  });
  forwardLines(child.stdout, send);
  forwardLines(child.stderr, send);
  const { promise, resolve, reject } = Promise.withResolvers<number | null>();
  child.on("error", reject);
  child.on("close", resolve);
  return { code: await promise, lastLine };
}
