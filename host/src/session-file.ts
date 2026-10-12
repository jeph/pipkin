import { realpath } from "node:fs/promises";
import { isAbsolute, join, relative, sep } from "node:path";
import { HostError } from "./env.js";

async function resolveExisting(path: string): Promise<string> {
  try {
    return await realpath(path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") throw new HostError("not_found", `Session not found: ${path}`);
    throw error;
  }
}

/**
 * The real path of the session file [path], refusing anything that resolves outside the sessions folder
 * (`outside_sessions`). A missing file throws `not_found`.
 */
export async function sessionFile(configDir: string, path: string): Promise<string> {
  const base = await resolveExisting(join(configDir, "sessions"));
  const target = await resolveExisting(path);
  const inside = relative(base, target);
  if (inside === "" || inside === ".." || inside.startsWith(`..${sep}`) || isAbsolute(inside)) {
    throw new HostError("outside_sessions", "That file is not inside Pipkin's sessions folder");
  }
  return target;
}
