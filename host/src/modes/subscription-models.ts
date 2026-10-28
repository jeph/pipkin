import { loadCatalogue } from "../catalogue.js";
import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { fillMissing, parseModelDef } from "../model-def.js";
import { fetchModelList } from "../model-list.js";
import { stringParam } from "../params.js";
import { createRuntime, loginType, modelListPath } from "../runtime.js";

/**
 * The models the stored API key of a provider Pi ships may use, in the provider's order, from its
 * OpenAI-style models list; no `models` for a provider without one. A model Pi knows comes with Pi's
 * settings (and `models.json`'s), others with what the list reports, filled from Pi's catalogue.
 * Failures are `bad_params`, `no_key` or one of `fetchModelList`'s codes.
 */
export async function subscriptionModels(ctx: ModeContext): Promise<JsonObject> {
  const providerId = stringParam(ctx.params, "providerId");
  if (loginType(providerId) !== "api_key") {
    throw new HostError("bad_params", `${providerId} is not a provider Pipkin connects with an API key`);
  }
  const path = modelListPath(providerId);
  if (path === undefined) return {};
  const runtime = await createRuntime(ctx);
  const baseUrl = runtime.getProvider(providerId)?.baseUrl;
  const apiKey = (await runtime.getAuth(providerId, { signal: ctx.signal }))?.auth.apiKey;
  if (baseUrl === undefined || apiKey === undefined) {
    throw new HostError("no_key", `No API key is saved for ${providerId}`, { key: "subscription_no_key" });
  }
  const listed = await fetchModelList(ctx, baseUrl, `${baseUrl.replace(/\/+$/, "")}${path}`, apiKey);
  const known = new Map(runtime.getModels(providerId).map((model) => [model.id, parseModelDef(model.id, model as unknown as JsonObject)]));
  const catalogue = await loadCatalogue(ctx, "openai-completions");
  return {
    models: [...listed.values()].map((detected) => known.get(detected.id) ?? fillMissing(detected, catalogue(detected.id))),
  };
}
