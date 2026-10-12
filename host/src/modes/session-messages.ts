import { loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { sessionFile } from "../session-file.js";

/** A session's messages as Pi RPC `get_messages` reports them, read from its file without starting Pi. */
export async function sessionMessages(ctx: ModeContext): Promise<JsonObject> {
  const path = await sessionFile(ctx.env.configDir, stringParam(ctx.params, "path"));
  const { SessionManager } = await loadPi(ctx.env.root);
  return { messages: SessionManager.open(path).buildSessionContext().messages };
}
