import { readFile, readlink, rename, symlink, unlink } from "node:fs/promises";

/**
 * Whether `pid` is a live process whose command line contains one of `commands`; a pid a dead process left behind may
 * since belong to another program.
 */
async function isLive(pid: number, commands: readonly string[]): Promise<boolean> {
  try {
    process.kill(pid, 0);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EPERM") return false;
  }
  const command = await readFile(`/proc/${pid}/cmdline`).catch(() => null);
  // Without a /proc (macOS, in tests) a live pid is taken to be one of `commands`.
  if (command === null) return process.platform !== "linux" && process.platform !== "android";
  return commands.some((name) => command.includes(name));
}

/**
 * The pid a lock at `path` names, or null when there is no lock or its holder is gone. A lock is a symlink whose target
 * is its holder's pid: creating one is atomic and fails when it exists, and it never exists without its pid. Android's
 * app sandbox refuses hard links, so `link` can't do this.
 */
export async function livePid(path: string, commands: readonly string[]): Promise<number | null> {
  const pid = Number(await readlink(path).catch(() => ""));
  return Number.isInteger(pid) && pid > 0 && (await isLive(pid, commands)) ? pid : null;
}

/**
 * Removes the lock at `path`, found with no live holder. A rename has one winner, so two processes that both found the
 * lock stale cannot both remove it. One that lost the race moved the winner's fresh lock aside, and puts it back.
 */
export async function clearStaleLock(path: string, commands: readonly string[]): Promise<void> {
  const stale = `${path}.stale.${process.pid}`;
  try {
    await rename(path, stale);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    return;
  }
  const holder = await livePid(stale, commands);
  if (holder !== null) await symlink(String(holder), path).catch(() => undefined);
  await unlink(stale).catch(() => undefined);
}
