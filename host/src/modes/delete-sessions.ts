import { unlink } from "node:fs/promises";
import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringArrayParam } from "../params.js";
import { sessionFile } from "../session-file.js";

/**
 * Deletes session files. Every path is checked before any is deleted, so one outside the sessions folder
 * deletes nothing; a file that is already gone counts as deleted.
 */
export async function deleteSessions(ctx: ModeContext): Promise<JsonObject> {
  const targets: string[] = [];
  for (const path of stringArrayParam(ctx.params, "paths")) {
    try {
      targets.push(await sessionFile(ctx.env.configDir, path));
    } catch (error) {
      if (!(error instanceof HostError && error.code === "not_found")) throw error;
    }
  }
  for (const target of targets) {
    try {
      await unlink(target);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }
  return {};
}
