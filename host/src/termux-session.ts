import { spawn } from "node:child_process";
import { open, readFile, rm } from "node:fs/promises";
import { join } from "node:path";
import type { HostEnv } from "./env.js";

/** How long the user's startup files get before Pi starts with the host's own environment. */
const CAPTURE_TIMEOUT_MS = 5000;
/** Variables that describe the capturing shell itself and would be stale in Pi. */
const SHELL_BOOKKEEPING: Record<string, true> = { PWD: true, OLDPWD: true, SHLVL: true, _: true };

/**
 * Runs Termux's `login` as an interactive login shell, the way a Termux session starts, and returns the environment its
 * profile and `~/.bashrc` leave behind. `env -0` writes to a file on fd 3, so whatever the startup files print stays out
 * of it. Undefined when the shell fails, never runs the command (a startup file that `exec`s another program), or
 * outlasts `CAPTURE_TIMEOUT_MS`.
 */
async function captureSessionEnv(env: HostEnv, base: NodeJS.ProcessEnv): Promise<Record<string, string> | undefined> {
  const file = join(env.root, `session-env-${process.pid}`);
  const output = await open(file, "w+");
  try {
    const child = spawn(join(env.prefix, "bin", "login"), ["-i", "-c", "env -0 >&3"], {
      env: base,
      stdio: ["ignore", "ignore", "ignore", output.fd],
      detached: true,
    });
    const { promise, resolve } = Promise.withResolvers<number | null>();
    child.on("error", () => resolve(null));
    child.on("exit", resolve);
    const timer = setTimeout(() => {
      if (child.pid !== undefined) process.kill(-child.pid, "SIGKILL");
    }, CAPTURE_TIMEOUT_MS);
    const code = await promise;
    clearTimeout(timer);
    if (code !== 0) return undefined;
    const variables: Record<string, string> = {};
    // By path: the shell's write moved the shared file offset to the end.
    for (const entry of (await readFile(file, "utf8")).split("\0")) {
      const separator = entry.indexOf("=");
      if (separator <= 0) continue;
      const name = entry.slice(0, separator);
      if (SHELL_BOOKKEEPING[name] === undefined) variables[name] = entry.slice(separator + 1);
    }
    return Object.keys(variables).length > 0 ? variables : undefined;
  } finally {
    await output.close();
    await rm(file, { force: true });
  }
}

/**
 * The environment for Pi: what a Termux session exports after its profile and `~/.bashrc`, such as API keys and PATH
 * additions, so Pi, its extensions and the commands it runs see what `pi` in a Termux terminal would. The startup files
 * run without the host's own variables, and Pipkin's config directory wins over one the user exports. Falls back to the
 * host's environment, minus its own variables, when the capture fails.
 */
export async function termuxSessionEnv(env: HostEnv): Promise<NodeJS.ProcessEnv> {
  const { PIPKIN_TOKEN: _token, PIPKIN_ROOT: _root, PI_CODING_AGENT_DIR: _configDir, ...base } = process.env;
  const session = (await captureSessionEnv(env, base)) ?? base;
  return { ...session, PI_CODING_AGENT_DIR: env.configDir };
}
