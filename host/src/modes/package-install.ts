import { spawn } from "node:child_process";
import { isConfigLocked } from "../config-lock.js";
import { HostError, piEntry } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { termuxSessionEnv } from "../termux-session.js";
import { forwardLines } from "./ensure.js";

/**
 * Runs `pi install <source>` in Pipkin's Pi, which adds the package to Pi's global settings. Each chat loads it the
 * next time its Pi starts. Pi's output arrives as progress lines; a failure reports Pi's last line.
 */
export async function packageInstall(ctx: ModeContext): Promise<JsonObject> {
  const source = stringParam(ctx.params, "source");
  // A restore swaps the configuration while it holds the lock, which would drop the settings Pi writes.
  if (await isConfigLocked(ctx.env)) {
    throw new HostError("config_busy", "Pipkin is backing up or restoring the configuration; try again in a moment");
  }
  let lastLine = "";
  const send = (line: string): void => {
    if (line.trim().length > 0) lastLine = line;
    ctx.conn.send({ type: "pipkin_progress", line });
  };
  const child = spawn(process.execPath, [await piEntry(ctx.env.root), "install", source], {
    cwd: ctx.env.home,
    env: await termuxSessionEnv(ctx.env),
    signal: ctx.signal,
    stdio: ["ignore", "pipe", "pipe"],
  });
  forwardLines(child.stdout, send);
  forwardLines(child.stderr, send);
  const { promise, resolve, reject } = Promise.withResolvers<number | null>();
  child.on("error", reject);
  child.on("close", resolve);
  const code = await promise;
  if (code === 0) return {};
  throw new HostError("install_failed", lastLine || `pi install failed (exit ${code ?? "signal"})`);
}
