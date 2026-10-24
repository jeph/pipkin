import { rm, unlink } from "node:fs/promises";
import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringArrayParam } from "../params.js";
import { sessionFile } from "../session-file.js";
import { containedFolder, sessionId, subagentFolder } from "../subagent-folders.js";

/**
 * Deletes session files, each with its subagents' transcripts. Every path, the subagent folders' included, is checked
 * before anything is deleted, so one outside the sessions folder deletes nothing; a file that is already gone counts
 * as deleted.
 */
export async function deleteSessions(ctx: ModeContext): Promise<JsonObject> {
  const targets: { file: string; folder: string | undefined }[] = [];
  for (const path of stringArrayParam(ctx.params, "paths")) {
    let file: string;
    try {
      file = await sessionFile(ctx.env.configDir, path);
    } catch (error) {
      if (error instanceof HostError && error.code === "not_found") continue;
      throw error;
    }
    // The id is in the file, so it's read before the file goes.
    const id = await sessionId(file);
    const folder = id === undefined ? undefined : subagentFolder(file, id);
    targets.push({ file, folder: folder && (await containedFolder(ctx.env.configDir, folder)) });
  }
  for (const { file, folder } of targets) {
    try {
      await unlink(file);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    if (folder) await rm(folder, { recursive: true, force: true });
  }
  return {};
}
