import { spawn } from "node:child_process";
import { withConfigLock } from "../config-lock.js";
import { HostError, installedPiVersion, piEntry } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { termuxSessionEnv } from "../termux-session.js";
import { forwardLines, isNpmNetworkError } from "./ensure.js";

/** The line `pi update` prints last when it fails. */
const PI_ERROR = /^Error: (.+)$/;

/**
 * Runs `pi update --extensions`, which updates every package in the user's Pi settings that isn't pinned to a version,
 * and leaves Pi itself at the version Pipkin pins. It runs from the home folder, like a terminal's `pi update`, and
 * holds the config lock, so no backup, restore or starting Pi sees a package half-installed. Pi's output becomes
 * progress lines.
 */
export async function updateExtensions(ctx: ModeContext): Promise<JsonObject> {
  if ((await installedPiVersion(ctx.env.root)) === null) throw new HostError("pi_missing", "The agent is not installed");
  return withConfigLock(ctx.env, async () => {
    let offline = false;
    let piError: string | undefined;
    const send = (line: string): void => {
      offline ||= isNpmNetworkError(line);
      piError = PI_ERROR.exec(line)?.[1] ?? piError;
      ctx.conn.send({ type: "pipkin_progress", line });
    };
    const child = spawn(process.execPath, [await piEntry(ctx.env.root), "update", "--extensions"], {
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
    if (offline) throw new HostError("npm_offline", "Couldn't download the extensions. Check your internet connection, then retry.");
    throw new HostError("update_failed", piError ?? `pi update failed (exit ${code ?? "signal"})`);
  });
}
