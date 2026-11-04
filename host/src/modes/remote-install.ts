import { spawn } from "node:child_process";
import { join } from "node:path";
import { HostError, withoutHostVariables } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { commandFailed, forwardLines } from "./ensure.js";

/** apt's and dpkg's reports that another install holds the package database, as one in a Termux session would. */
const BUSY = /Could not get lock|Unable to acquire the dpkg frontend lock|Unable to lock the administration directory|dpkg frontend lock|locked by another process/i;
/** apt's reports that it couldn't reach a mirror. */
const OFFLINE = /Failed to fetch|Unable to fetch|Temporary failure resolving|Could not resolve|Could not connect to|Connection timed out|Network is unreachable|No address associated/i;

/**
 * Installs Termux's OpenSSH (`pkg install -y openssh`), forwarding its output as progress lines. A package database
 * another install holds and an unreachable mirror fail with their own messages; nothing is left half-done that the
 * next attempt can't finish.
 */
export async function remoteInstall(ctx: ModeContext): Promise<JsonObject> {
  let busy = false;
  let offline = false;
  const send = (line: string): void => {
    busy ||= BUSY.test(line);
    offline ||= OFFLINE.test(line);
    ctx.conn.send({ type: "pipkin_progress", line });
  };
  const child = spawn(join(ctx.env.prefix, "bin", "pkg"), ["install", "-y", "openssh"], {
    cwd: ctx.env.home,
    env: withoutHostVariables(process.env),
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
  if (busy) {
    throw new HostError("remote_install_busy", "Termux's package manager is busy with another install. Try again once it's done.", {
      key: "remote_install_busy",
    });
  }
  if (offline) {
    throw new HostError("remote_install_offline", "Couldn't download OpenSSH. Check your internet connection, then try again.", {
      key: "remote_install_offline",
    });
  }
  throw commandFailed("install_failed", "pkg install openssh", code);
}
