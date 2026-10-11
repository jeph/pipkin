import { loadCatalogue } from "../catalogue.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringArrayParam, stringParam } from "../params.js";

/** Pi's built-in settings for hand-entered model ids, in request order; an unknown id is `{id}`. */
export async function modelDefaults(ctx: ModeContext): Promise<JsonObject> {
  const ids = stringArrayParam(ctx.params, "ids");
  const api = stringParam(ctx.params, "api");
  const catalogue = await loadCatalogue(ctx, api);
  return { models: ids.map((id) => catalogue(id) ?? { id }) };
}
