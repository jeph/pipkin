import { HostError, loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { THINKING_LEVELS } from "../model-def.js";
import { nullableStringParam } from "../params.js";
import { openSettings } from "../settings.js";

/**
 * Saves the chat picker's choice as Pi's defaults for new sessions, as Pi's own model picker does:
 * `defaultProvider`/`defaultModel` for a model pick, `defaultThinkingLevel` for a level pick.
 * Pi's startup uses the saved model only while it is available and in `enabledModels`.
 */
export async function defaultModel(ctx: ModeContext): Promise<JsonObject> {
  const provider = nullableStringParam(ctx.params, "provider");
  const modelId = nullableStringParam(ctx.params, "modelId");
  const thinkingLevel = nullableStringParam(ctx.params, "thinkingLevel");
  if ((provider === null) !== (modelId === null) || provider === "" || modelId === "") {
    throw new HostError("bad_params", "provider and modelId go together");
  }
  if (thinkingLevel !== null && !(THINKING_LEVELS as readonly string[]).includes(thinkingLevel)) {
    throw new HostError("bad_params", `Unknown thinking level ${thinkingLevel}`);
  }
  if (provider === null && thinkingLevel === null) throw new HostError("bad_params", "Nothing to save");
  const settings = openSettings(ctx, await loadPi(ctx.env.root));
  if (provider !== null && modelId !== null) settings.manager.setDefaultModelAndProvider(provider, modelId);
  if (thinkingLevel !== null) settings.manager.setDefaultThinkingLevel(thinkingLevel as (typeof THINKING_LEVELS)[number]);
  await settings.manager.flush();
  const [failure] = settings.manager.drainErrors();
  if (failure !== undefined) throw failure.error;
  return {};
}
