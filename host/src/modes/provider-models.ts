import { loadCatalogue } from "../catalogue.js";
import { HostError } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { fillMissing } from "../model-def.js";
import { fetchModelList } from "../model-list.js";
import { NO_API_KEY } from "../models-json.js";
import { stringParam } from "../params.js";

/**
 * Lists an OpenAI-compatible endpoint's models with the capabilities it reports, filling the
 * fields it leaves out from Pi's built-in catalogue. Failures are `bad_url` or one of
 * `fetchModelList`'s codes.
 */
export async function providerModels(ctx: ModeContext): Promise<JsonObject> {
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const apiKey = stringParam(ctx.params, "apiKey") || NO_API_KEY;
  const api = stringParam(ctx.params, "api");
  const protocol = URL.parse(baseUrl)?.protocol;
  if (protocol !== "http:" && protocol !== "https:") {
    throw new HostError("bad_url", `${baseUrl} is not an http:// or https:// URL`);
  }
  const detected = await fetchModelList(ctx, baseUrl, `${baseUrl.replace(/\/+$/, "")}/models`, apiKey);
  const catalogue = await loadCatalogue(ctx, api);
  const models = [...detected.values()].map((model) => fillMissing(model, catalogue(model.id)));
  return { models: models.toSorted((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) };
}
