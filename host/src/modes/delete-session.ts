import { realpath, unlink } from "node:fs/promises";
import { isAbsolute, join, relative, sep } from "node:path";
import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";

async function resolveExisting(path: string): Promise<string> {
  try {
    return await realpath(path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") throw new HostError("not_found", `Session not found: ${path}`);
    throw error;
  }
}

/** Deletes a session file, refusing anything that resolves outside the sessions directory. */
export async function deleteSession(ctx: ModeContext): Promise<JsonObject> {
  const path = stringParam(ctx.params, "path");
  const base = await resolveExisting(join(ctx.env.configDir, "sessions"));
  const target = await resolveExisting(path);
  const inside = relative(base, target);
  if (inside === "" || inside === ".." || inside.startsWith(`..${sep}`) || isAbsolute(inside)) {
    throw new HostError("outside_sessions", "That file is not inside Pi's sessions folder");
  }
  await unlink(target);
  return {};
}
