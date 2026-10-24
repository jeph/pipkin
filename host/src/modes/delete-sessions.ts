import { rm, unlink } from "node:fs/promises";
import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringArrayParam } from "../params.js";
import { sessionFile } from "../session-file.js";
import { sessionId, subagentFolder } from "../subagent-folders.js";

/**
 * Deletes session files, each with its subagents' transcripts. Every path is checked before any is deleted, so one
 * outside the sessions folder deletes nothing; a file that is already gone counts as deleted.
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
    // The id is in the file, so it's read before the file goes.
    const id = await sessionId(target);
    try {
      await unlink(target);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    const folder = id === undefined ? undefined : subagentFolder(target, id);
    if (folder) await rm(folder, { recursive: true, force: true });
  }
  return {};
}
