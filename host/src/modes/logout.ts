import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { createRuntime } from "../runtime.js";

export async function logout(ctx: ModeContext): Promise<JsonObject> {
  const providerId = stringParam(ctx.params, "providerId");
  const runtime = await createRuntime(ctx);
  await runtime.logout(providerId, { signal: ctx.signal });
  return {};
}
