import { loadPi } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { type ModelDef, objectsById, parseModelDef } from "../model-def.js";
import { readModelsJson } from "../models-json.js";
import { BUILTIN_PROVIDERS, createRuntime } from "../runtime.js";
import { type ScopeRef, scopeResolver, scopeUniverse } from "../scope.js";
import { openSettings, readGlobalSettings, userNpmPackages } from "../settings.js";

export async function authStatus(ctx: ModeContext): Promise<JsonObject> {
  const pi = await loadPi(ctx.env.root);
  const runtime = await createRuntime(ctx);
  const builtinIds = new Set((await createRuntime(ctx, { builtinOnly: true })).getProviders().map((provider) => provider.id));

  let providers: JsonObject = {};
  let modelsError = runtime.getError();
  try {
    ({ providers } = await readModelsJson(ctx.env.configDir));
  } catch (error) {
    modelsError = error instanceof Error ? error.message : String(error);
  }

  // `null` scope: settings.json has no enabledModels (or is invalid), so every model shows.
  let scope: ScopeRef[] | null = null;
  let settingsError: string | undefined;
  try {
    const { enabledModels } = openSettings(ctx, pi);
    if (enabledModels.length > 0) scope = await scopeResolver(pi, await scopeUniverse(runtime, ctx.signal))(enabledModels);
  } catch (error) {
    settingsError = error instanceof Error ? error.message : String(error);
  }

  const builtin: JsonObject[] = [];
  for (const id of BUILTIN_PROVIDERS) {
    const check = await runtime.checkAuth(id, { signal: ctx.signal });
    const entry = providers[id];
    builtin.push({
      id,
      name: runtime.getProvider(id)?.name ?? id,
      configured: check !== undefined,
      type: check?.type ?? null,
      // Effective settings: Pi's catalogue with models.json overrides and extra models applied.
      models: runtime.getModels(id).map((model) => parseModelDef(model.id, model as unknown as JsonObject)),
      extraIds: [...objectsById(isObject(entry) ? entry.models : undefined).keys()],
      enabled: scope?.filter((ref) => ref.provider === id).map((ref) => ref.id) ?? null,
    });
  }

  const custom: JsonObject[] = [];
  for (const [id, provider] of Object.entries(providers)) {
    // Entries for built-in providers hold overrides, not custom providers.
    if (!isObject(provider) || builtinIds.has(id)) continue;
    const models = Array.isArray(provider.models) ? provider.models : [];
    custom.push({
      id,
      baseUrl: provider.baseUrl,
      api: provider.api,
      // The editor shows and reuses the stored key, so editing never asks for it again.
      apiKey: typeof provider.apiKey === "string" ? provider.apiKey : "",
      // Wrongly typed managed fields are left out; provider_save rewrites them from the editor.
      models: models.flatMap((model): ModelDef[] => {
        if (typeof model === "string") return [{ id: model }];
        return isObject(model) && typeof model.id === "string" ? [parseModelDef(model.id, model)] : [];
      }),
    });
  }

  return {
    builtin,
    custom,
    scope: scope?.map((ref) => `${ref.provider}/${ref.id}`) ?? null,
    npmPackages: [...userNpmPackages(await readGlobalSettings(ctx.env.configDir))],
    ...(modelsError === undefined ? {} : { modelsError }),
    ...(settingsError === undefined ? {} : { settingsError }),
  };
}
