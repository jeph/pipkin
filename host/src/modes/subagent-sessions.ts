import { readFile, stat } from "node:fs/promises";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { sessionFile } from "../session-file.js";
import { containedFolder, sessionId, subagentFolder, transcriptFiles } from "../subagent-folders.js";

/** The custom entry the Pipkin subagents extension writes into each transcript, linking it to the parent's call. */
const ENTRY_TYPE = "pipkin-subagent";

/** What one transcript says about its run, or undefined when it isn't a subagent's. */
async function describe(path: string): Promise<JsonObject | undefined> {
  let content: string;
  try {
    content = await readFile(path, "utf8");
  } catch {
    return undefined;
  }
  let header: JsonObject | undefined;
  let link: JsonObject | undefined;
  let messageCount = 0;
  for (const line of content.split("\n")) {
    let entry: unknown;
    try {
      entry = JSON.parse(line);
    } catch {
      continue;
    }
    if (!isObject(entry)) continue;
    if (!header) {
      if (entry.type !== "session") return undefined;
      header = entry;
    } else if (entry.type === "message") messageCount++;
    else if (entry.type === "custom" && entry.customType === ENTRY_TYPE && isObject(entry.data)) link ??= entry.data;
  }
  if (!header || !link) return undefined;
  const created = typeof header.timestamp === "string" ? Date.parse(header.timestamp) : Number.NaN;
  return {
    path,
    toolCallId: typeof link.toolCallId === "string" ? link.toolCallId : null,
    agent: typeof link.agent === "string" ? link.agent : null,
    description: typeof link.description === "string" ? link.description : null,
    created: Number.isNaN(created) ? null : created,
    modified: Math.round((await stat(path)).mtimeMs),
    messageCount,
  };
}

/** The subagent transcripts of the session file `path`, oldest first; none when it has no session header. */
export async function subagentSessions(ctx: ModeContext): Promise<JsonObject> {
  const parent = await sessionFile(ctx.env.configDir, stringParam(ctx.params, "path"));
  const id = await sessionId(parent);
  const folder = id === undefined ? undefined : subagentFolder(parent, id);
  const contained = folder && (await containedFolder(ctx.env.configDir, folder));
  const found = contained ? await Promise.all((await transcriptFiles(contained)).map(describe)) : [];
  const sessions = found.filter((session): session is JsonObject => session !== undefined);
  sessions.sort((a, b) => Number(a.created ?? 0) - Number(b.created ?? 0));
  return { sessions };
}
