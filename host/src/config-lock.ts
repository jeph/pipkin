import { readFile, readlink, rename, symlink, unlink } from "node:fs/promises";
import { join } from "node:path";
import { HostError, type HostEnv } from "./env.js";

const LOCK_FILE = "config.lock";

/** Whether `pid` is a live host process; a pid a dead host left behind may since belong to another program. */
async function isLiveHost(pid: number): Promise<boolean> {
  try {
    process.kill(pid, 0);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EPERM") return false;
  }
  const command = await readFile(`/proc/${pid}/cmdline`).catch(() => null);
  // Without a /proc (macOS, in tests) a live pid is taken to be the host.
  if (command === null) return process.platform !== "linux" && process.platform !== "android";
  return command.includes("host.mjs");
}

/** The live host pid a lock at `path` names, or null when there is no lock or its host is gone. */
async function livePid(path: string): Promise<number | null> {
  const pid = Number(await readlink(path).catch(() => ""));
  return Number.isInteger(pid) && pid > 0 && (await isLiveHost(pid)) ? pid : null;
}

/** True while a move, backup, restore or delete holds the config lock, so Pi must not start on a half-swapped config. */
export async function isConfigLocked(env: HostEnv): Promise<boolean> {
  return (await livePid(join(env.root, LOCK_FILE))) !== null;
}

/**
 * Runs `run` holding the config lock: one move, backup, restore or delete at a time, across host processes. A lock left
 * by a host that died is taken over; one a live host holds fails with `busy`.
 *
 * The lock is a symlink whose target is the holder's pid: creating one is atomic and fails when it exists, and it never
 * exists without its pid. Android's app sandbox refuses hard links, so `link` can't do this.
 */
export async function withConfigLock<T>(env: HostEnv, run: () => Promise<T>): Promise<T> {
  const path = join(env.root, LOCK_FILE);
  const pid = String(process.pid);
  for (let attempt = 0; ; attempt++) {
    try {
      await symlink(pid, path);
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    }
    if ((await livePid(path)) !== null || attempt === 2) {
      throw new HostError("busy", "Another backup, restore or move of the configuration is running.");
    }
    // A rename has one winner, so two hosts that both found the lock stale cannot both remove it. A host that lost
    // the race moved the winner's fresh lock aside, and puts it back.
    const stale = `${path}.stale.${pid}`;
    try {
      await rename(path, stale);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      continue;
    }
    const holder = await livePid(stale);
    if (holder !== null) await symlink(String(holder), path).catch(() => undefined);
    await unlink(stale).catch(() => undefined);
  }
  try {
    return await run();
  } finally {
    await unlink(path).catch(() => undefined);
  }
}
