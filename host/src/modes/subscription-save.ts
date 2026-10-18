import { isDeepStrictEqual } from "node:util";
import { HostError, loadPi } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import {
  MANAGED_FIELDS,
  mergeManagedFields,
  type ModelDef,
  modelDefsParam,
  objectsById,
  parseModelDef,
  storedCostTiers,
  withoutManagedFields,
} from "../model-def.js";
import { readModelsJson, writeModelsJson } from "../models-json.js";
import { stringArrayParam, stringParam } from "../params.js";
import { BUILTIN_PROVIDERS, createRuntime } from "../runtime.js";
import { putDefaultFirst, rebuildForProvider, scopeResolver, scopeUniverse } from "../scope.js";
import { openSettings, saveEnabledModels } from "../settings.js";

/** The override for a built-in model: only the managed fields that differ from Pi's catalogue. */
function override(def: ModelDef, base: ModelDef, stored: JsonObject | undefined): JsonObject {
  const result = withoutManagedFields(stored);
  for (const field of MANAGED_FIELDS) {
    if (def[field] !== undefined && !isDeepStrictEqual(def[field], base[field])) result[field] = def[field];
  }
  // Pi merges override costs into the catalogue's, so stored tiers stay valid with or without new rates.
  const tiers = storedCostTiers(stored);
  if (tiers !== undefined) result.cost = { ...(isObject(result.cost) ? result.cost : {}), tiers };
  return result;
}

/**
 * Saves the per-model settings of a provider Pi ships and which of its models show. Built-in models
 * get `providers.<id>.modelOverrides`, other ids `providers.<id>.models`; visibility is settings.json
 * `enabledModels`, rebuilt so other providers keep exactly the models they had.
 */
export async function subscriptionSave(ctx: ModeContext): Promise<JsonObject> {
  const providerId = stringParam(ctx.params, "providerId");
  if (!BUILTIN_PROVIDERS.includes(providerId)) {
    throw new HostError("bad_params", `${providerId} is not a provider Pipkin connects`);
  }
  const enabled = stringArrayParam(ctx.params, "enabled");
  const models = modelDefsParam(ctx.params, "models");

  const pi = await loadPi(ctx.env.root);
  // Both files are checked before either is written.
  const document = await readModelsJson(ctx.env.configDir);
  const settings = openSettings(ctx, pi);
  const catalogue = new Map(
    (await createRuntime(ctx, { builtinOnly: true }))
      .getModels(providerId)
      .map((model) => [model.id, parseModelDef(model.id, model as unknown as JsonObject)]),
  );

  const before = document.providers[providerId];
  const entry: JsonObject = isObject(before) ? structuredClone(before) : {};
  const storedModels = objectsById(entry.models);
  const overrides: JsonObject = isObject(entry.modelOverrides) ? entry.modelOverrides : {};
  const extras: JsonObject[] = [];
  const ids = new Set(catalogue.keys());
  for (const def of models) {
    const base = catalogue.get(def.id);
    const storedOverride = overrides[def.id];
    const stored = isObject(storedOverride) ? storedOverride : undefined;
    let next: JsonObject;
    if (base === undefined || storedModels.has(def.id)) {
      // Ids Pi doesn't ship, and ids the agent defined in full (replacing Pi's), live in `models`.
      extras.push(mergeManagedFields(def, storedModels.get(def.id)));
      ids.add(def.id);
      next = withoutManagedFields(stored);
    } else {
      next = override(def, base, stored);
    }
    if (Object.keys(next).length > 0) overrides[def.id] = next;
    else delete overrides[def.id];
  }

  const unknown = enabled.find((id) => !ids.has(id));
  if (unknown !== undefined) throw new HostError("bad_params", `"${unknown}" is not a ${providerId} model`);

  if (extras.length > 0) entry.models = extras;
  else delete entry.models;
  if (Object.keys(overrides).length > 0) entry.modelOverrides = overrides;
  else delete entry.modelOverrides;
  if (Object.keys(entry).length > 0) document.providers[providerId] = entry;
  else delete document.providers[providerId];
  if (!isDeepStrictEqual(before, document.providers[providerId])) await writeModelsJson(ctx.env.configDir, document);

  const all = [...ids].every((id) => enabled.includes(id));
  if (settings.enabledModels.length === 0 && all) return {};
  // Created after the write, so the runtime already lists this provider's new models.
  const universe = await scopeUniverse(await createRuntime(ctx), ctx.signal);
  const resolve = scopeResolver(pi, universe);
  const patterns = await rebuildForProvider(settings.enabledModels, providerId, all ? "all" : enabled, universe, resolve);
  await saveEnabledModels(settings, await putDefaultFirst(patterns, settings.defaultRef, resolve));
  return {};
}
