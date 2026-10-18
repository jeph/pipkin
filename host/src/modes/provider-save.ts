import { HostError, loadPi } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { mergeManagedFields, modelDefsParam, objectsById } from "../model-def.js";
import { readModelsJson, writeModelsJson } from "../models-json.js";
import { stringParam } from "../params.js";
import { createRuntime } from "../runtime.js";
import { keepProviderInScope, replaceProviderModels, scopeResolver, scopeUniverse } from "../scope.js";
import { openSettingsIfValid, saveEnabledModels } from "../settings.js";

const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,39}$/;
const APIS = ["openai-completions", "openai-responses"];

/**
 * Adds or replaces one OpenAI-compatible provider in `models.json`, keeping everything else, and keeps
 * its models visible when settings.json `enabledModels` limits the model list.
 */
export async function providerSave(ctx: ModeContext): Promise<JsonObject> {
  const id = stringParam(ctx.params, "id");
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const api = stringParam(ctx.params, "api");
  const apiKey = stringParam(ctx.params, "apiKey");
  const models = modelDefsParam(ctx.params, "models");
  if (!PROVIDER_ID.test(id)) {
    throw new HostError("bad_id", "Use 1–40 lowercase letters, digits or dashes, starting with a letter or digit", {
      key: "provider_id_invalid",
    });
  }
  if (!APIS.includes(api)) throw new HostError("bad_params", `Unsupported API type ${api}`);

  const document = await readModelsJson(ctx.env.configDir);
  // Built-in ids (including the subscriptions, whose overrides also live under `providers`) are never custom.
  const builtin = await createRuntime(ctx, { builtinOnly: true });
  if (builtin.getProviders().some((provider) => provider.id === id)) {
    throw new HostError("builtin_id", `"${id}" is a built-in provider; choose another ID`, { key: "provider_id_builtin", args: { id } });
  }
  if (apiKey === "") throw new HostError("bad_params", "An API key is required");

  const pi = await loadPi(ctx.env.root);
  const settings = openSettingsIfValid(ctx, pi);
  const runtime = await createRuntime(ctx);

  const existing = document.providers[id];
  const entry = isObject(existing) ? existing : {};
  const existingModels = objectsById(entry.models);
  document.providers[id] = {
    ...entry,
    baseUrl,
    api,
    apiKey,
    models: models.map((model) => mergeManagedFields(model, existingModels.get(model.id))),
  };
  await writeModelsJson(ctx.env.configDir, document);

  if (settings !== undefined && settings.enabledModels.length > 0) {
    const saved = models.map((model) => ({ provider: id, id: model.id, name: model.name ?? model.id }));
    const resolve = scopeResolver(pi, replaceProviderModels(await scopeUniverse(runtime, ctx.signal), id, saved));
    const patterns = await keepProviderInScope(settings.enabledModels, id, settings.defaultRef, resolve);
    if (patterns !== undefined) await saveEnabledModels(settings, patterns);
  }
  return {};
}
