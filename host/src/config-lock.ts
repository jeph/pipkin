import { symlink, unlink } from "node:fs/promises";
import { join } from "node:path";
import { HostError, type HostEnv } from "./env.js";
import { clearStaleLock, livePid } from "./pid-lock.js";

const LOCK_FILE = "config.lock";
/** The config lock's holder is always the host that took it. */
const HOLDERS = ["host.mjs"];

/**
 * True while a backup, restore, delete or extension update holds the config lock, so Pi must not start on a
 * half-changed config.
 */
export async function isConfigLocked(env: HostEnv): Promise<boolean> {
  return (await livePid(join(env.root, LOCK_FILE), HOLDERS)) !== null;
}

/** The refusal of a mode that must not run while [isConfigLocked], such as starting Pi or installing a package. */
export function configBusy(): HostError {
  return new HostError("config_busy", "Pipkin is backing up, restoring or updating the configuration; try again in a moment", {
    key: "config_busy",
  });
}

/**
 * Runs `run` holding the config lock: one backup, restore, delete or extension update at a time, across host
 * processes. A lock left by a host that died is taken over; one a live host holds fails with `busy`.
 */
export async function withConfigLock<T>(env: HostEnv, run: () => Promise<T>): Promise<T> {
  const path = join(env.root, LOCK_FILE);
  for (let attempt = 0; ; attempt++) {
    try {
      await symlink(String(process.pid), path);
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    }
    if ((await livePid(path, HOLDERS)) !== null || attempt === 2) {
      throw new HostError("busy", "Another backup, restore or extension update is running.", { key: "config_lock_busy" });
    }
    await clearStaleLock(path, HOLDERS);
  }
  try {
    return await run();
  } finally {
    await unlink(path).catch(() => undefined);
  }
}
