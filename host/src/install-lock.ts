import { rename, symlink, unlink } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { clearStaleLock, livePid } from "./pid-lock.js";

/** Names the host that holds the lock, for as long as that host runs. */
const LOCK_FILE = "install.lock";
const HOST = ["host.mjs"];
/**
 * Names the `npm ci` the holder started, which outlives a host whose install was cancelled while npm rolls back what
 * it unpacked. Only the holder writes it.
 */
const NPM_FILE = "install.lock.npm";
const NPM = ["npm"];
const POLL_MS = 500;

export interface InstallLock {
  /** Records [pid] as this install's `npm ci`, so no other install starts until it exits. */
  recordNpm(pid: number): Promise<void>;
}

/**
 * Runs `run` holding the install lock: one install of the runtime at a time, across host processes, and none while an
 * `npm ci` an earlier install started is still running, even when the host that started it is gone. Waits while a live
 * host holds the lock, then while the npm it recorded runs, until [signal] aborts; a lock whose host is gone is taken
 * over.
 */
export async function withInstallLock<T>(root: string, signal: AbortSignal, run: (lock: InstallLock) => Promise<T>): Promise<T> {
  const path = join(root, LOCK_FILE);
  const npmPath = join(root, NPM_FILE);
  for (;;) {
    try {
      await symlink(String(process.pid), path);
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    }
    if ((await livePid(path, HOST)) === null) await clearStaleLock(path, HOST);
    else await sleep(POLL_MS, undefined, { signal });
  }
  const lock: InstallLock = {
    async recordNpm(pid) {
      const next = `${npmPath}.${process.pid}.tmp`;
      await unlink(next).catch(() => undefined);
      await symlink(String(pid), next);
      await rename(next, npmPath);
    },
  };
  try {
    // Checked under the lock, where no other install can record an npm: once this one has exited, none is left running.
    while ((await livePid(npmPath, NPM)) !== null) await sleep(POLL_MS, undefined, { signal });
    return await run(lock);
  } finally {
    // A cancelled install's npm may still be rolling back; its record keeps the next install waiting until it exits.
    if ((await livePid(npmPath, NPM)) === null) await unlink(npmPath).catch(() => undefined);
    await unlink(path).catch(() => undefined);
  }
}
