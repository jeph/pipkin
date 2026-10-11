import { loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { readModelsJson, writeModelsJson } from "../models-json.js";
import { stringParam } from "../params.js";
import { dropProvider } from "../scope.js";
import { openSettingsIfValid, saveEnabledModels } from "../settings.js";

/** Removes a custom provider and the settings.json `enabledModels` patterns written for it. */
export async function providerRemove(ctx: ModeContext): Promise<JsonObject> {
  const id = stringParam(ctx.params, "id");
  const document = await readModelsJson(ctx.env.configDir);
  const settings = openSettingsIfValid(ctx, await loadPi(ctx.env.root));
  if (id in document.providers) {
    delete document.providers[id];
    await writeModelsJson(ctx.env.configDir, document);
  }
  if (settings !== undefined) {
    const patterns = dropProvider(settings.enabledModels, id);
    if (patterns.length !== settings.enabledModels.length) await saveEnabledModels(settings, patterns);
  }
  return {};
}
