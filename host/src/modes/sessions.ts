import { loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";

const FIRST_MESSAGE_CHARS = 200;

export async function sessions(ctx: ModeContext): Promise<JsonObject> {
  const { SessionManager } = await loadPi(ctx.env.root);
  const all = await SessionManager.listAll(undefined, ctx.signal);
  all.sort((a, b) => b.modified.getTime() - a.modified.getTime());
  return {
    sessions: all.map((session) => ({
      path: session.path,
      id: session.id,
      cwd: session.cwd,
      name: session.name,
      created: session.created.getTime(),
      modified: session.modified.getTime(),
      messageCount: session.messageCount,
      // Code points, so the cut never leaves a lone surrogate.
      firstMessage: Array.from(session.firstMessage).slice(0, FIRST_MESSAGE_CHARS).join(""),
    })),
  };
}
