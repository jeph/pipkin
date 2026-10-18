import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { HostError } from "./env.js";

/** Tests point this at a fake process table; on a machine without `/proc` no process is ever found. */
const PROC = process.env.PIPKIN_PROC_DIR ?? "/proc";
/** Pi gets this long after its chat closed: the rpc host waits 5 s for it to exit before SIGTERM, then 5 s before SIGKILL. */
export const PI_EXIT_TIMEOUT_MS = 20_000;
const POLL_MS = 250;

/**
 * The ids of running Pi processes whose config folder is `configDir`: their command line runs the Pi package and
 * their environment sets `PI_CODING_AGENT_DIR` to it. That covers Pi started for a chat, Pi a chat's agent started
 * through bash, and a stale Pi left behind by a host that died.
 */
export async function runningPi(configDir: string): Promise<number[]> {
  let entries: string[];
  try {
    entries = await readdir(PROC);
  } catch {
    return [];
  }
  // Each variable in `environ` ends with a NUL; a leading one makes the first variable match like the rest.
  const needle = Buffer.from(`\0PI_CODING_AGENT_DIR=${configDir}\0`);
  const found: number[] = [];
  for (const entry of entries) {
    if (!/^\d+$/.test(entry) || Number(entry) === process.pid) continue;
    try {
      const command = await readFile(join(PROC, entry, "cmdline"));
      if (!command.includes("pi-coding-agent")) continue;
      const environment = await readFile(join(PROC, entry, "environ"));
      if (Buffer.concat([Buffer.alloc(1), environment]).includes(needle)) found.push(Number(entry));
    } catch {
      // The process exited while it was being read, or is not ours to read.
    }
  }
  return found;
}

/** Waits until no Pi uses `configDir`; throws `pi_running` once `timeoutMs` passes with one still running. */
export async function waitForPiExit(configDir: string, signal: AbortSignal, timeoutMs = PI_EXIT_TIMEOUT_MS): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const running = await runningPi(configDir);
    if (running.length === 0) return;
    if (Date.now() >= deadline) {
      const processes = running.join(", ");
      throw new HostError(
        "pi_running",
        `Pi is still running (process ${processes}). Stop it, for example by quitting pi in a terminal, and try again.`,
        { key: "pi_running", args: { processes } },
      );
    }
    await sleep(POLL_MS, undefined, { signal });
  }
}
