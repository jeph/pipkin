import { spawn } from "node:child_process";
import { withConfigLock } from "../config-lock.js";
import { HostError, installedPiVersion, piEntry } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { termuxSessionEnv } from "../termux-session.js";
import { commandFailed, forwardLines, isNpmNetworkError } from "./ensure.js";

/** The line `pi update` prints last when it fails. */
const PI_ERROR = /^Error: (.+)$/;
/** How long Pi and the npm or git it runs get to end after SIGTERM before SIGKILL; within main's abort grace. */
const KILL_AFTER_MS = 2000;

/**
 * Runs `pi update --extensions`, which updates every package in the user's Pi settings that isn't pinned to a version,
 * and leaves Pi itself at the version Pipkin pins. It runs from the home folder, like a terminal's `pi update`, and
 * holds the config lock, so no backup, restore or starting Pi sees a package half-installed. Pi's output becomes
 * progress lines.
 *
 * Pi runs in its own process group. When Pipkin hangs up, the whole group is stopped, including the npm or git Pi
 * started, and the lock is released only once all of them have exited.
 */
export async function updateExtensions(ctx: ModeContext): Promise<JsonObject> {
  if ((await installedPiVersion(ctx.env.root)) === null) {
    throw new HostError("pi_missing", "The agent is not installed", { key: "agent_not_installed" });
  }
  return withConfigLock(ctx.env, async () => {
    let offline = false;
    let piError: string | undefined;
    const send = (line: string): void => {
      offline ||= isNpmNetworkError(line);
      piError = PI_ERROR.exec(line)?.[1] ?? piError;
      ctx.conn.send({ type: "pipkin_progress", line });
    };
    const env = { ...(await termuxSessionEnv(ctx.env)), NPM_CONFIG_UPDATE_NOTIFIER: "false" };
    const child = spawn(process.execPath, [await piEntry(ctx.env.root), "update", "--extensions"], {
      cwd: ctx.env.home,
      env,
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const signalGroup = (signal: NodeJS.Signals): void => {
      try {
        if (child.pid !== undefined) process.kill(-child.pid, signal);
      } catch {
        // The group has already exited.
      }
    };
    const stop = (): void => {
      signalGroup("SIGTERM");
      setTimeout(() => signalGroup("SIGKILL"), KILL_AFTER_MS).unref();
    };
    if (ctx.signal.aborted) stop();
    else ctx.signal.addEventListener("abort", stop, { once: true });
    forwardLines(child.stdout, send);
    forwardLines(child.stderr, send);
    const { promise, resolve, reject } = Promise.withResolvers<number | null>();
    child.on("error", reject);
    // `close` waits for every process holding Pi's output, and npm and git inherit it, so it fires once they're gone.
    child.on("close", resolve);
    const code = await promise;
    ctx.signal.removeEventListener("abort", stop);
    if (code === 0) return {};
    if (offline) {
      throw new HostError("npm_offline", "Couldn't download the extensions. Check your internet connection, then retry.", {
        key: "extensions_download_offline",
      });
    }
    if (piError !== undefined) throw new HostError("update_failed", piError);
    throw commandFailed("update_failed", "pi update", code);
  });
}
