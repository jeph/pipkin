import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { readModelsJson, writeModelsJson } from "../models-json.js";
import { stringParam } from "../params.js";

export async function providerRemove(ctx: ModeContext): Promise<JsonObject> {
  const id = stringParam(ctx.params, "id");
  const document = await readModelsJson(ctx.env.configDir);
  if (!(id in document.providers)) return {};
  delete document.providers[id];
  await writeModelsJson(ctx.env.configDir, document);
  return {};
}
