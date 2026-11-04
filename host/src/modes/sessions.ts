import { stat } from "node:fs/promises";
import { loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { remotePaths } from "../remote/paths.js";
import { readStandIn } from "../remote/stand-ins.js";
import { countSubagents } from "../subagent-folders.js";

const FIRST_MESSAGE_CHARS = 200;

async function isDirectory(path: string): Promise<boolean> {
  try {
    return (await stat(path)).isDirectory();
  } catch {
    return false;
  }
}

/**
 * Every session, newest first; `cwdExists` tells whether its workspace folder is still there, `subagents` how many
 * subagent transcripts it has. A session in a remote workspace also has `remote: { hostId, path }`.
 */
export async function sessions(ctx: ModeContext): Promise<JsonObject> {
  const { SessionManager } = await loadPi(ctx.env.root);
  const all = await SessionManager.listAll(undefined, ctx.signal);
  all.sort((a, b) => b.modified.getTime() - a.modified.getTime());
  const cwds = [...new Set(all.map((session) => session.cwd))];
  const remotes = remotePaths(ctx.env.configDir, ctx.env.prefix, ctx.env.home).remotes;
  const [exists, standIns, subagents] = await Promise.all([
    Promise.all(cwds.map(isDirectory)),
    Promise.all(cwds.map((cwd) => readStandIn(remotes, cwd))),
    Promise.all(all.map((session) => countSubagents(session.path, session.id))),
  ]);
  const existing = new Set(cwds.filter((_, index) => exists[index]));
  const remoteFields = new Map(cwds.map((cwd, index) => [cwd, standIns[index] ? { remote: standIns[index] } : {}]));
  return {
    sessions: all.map((session, index) => ({
      path: session.path,
      id: session.id,
      cwd: session.cwd,
      cwdExists: existing.has(session.cwd),
      name: session.name,
      created: session.created.getTime(),
      modified: session.modified.getTime(),
      messageCount: session.messageCount,
      // Code points, so the cut never leaves a lone surrogate.
      firstMessage: Array.from(session.firstMessage).slice(0, FIRST_MESSAGE_CHARS).join(""),
      subagents: subagents[index] ?? 0,
      ...remoteFields.get(session.cwd),
    })),
  };
}
