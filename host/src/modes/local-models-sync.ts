import { loadPi, type PiSdk } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { type ModelDef, modelDefsParam, TEXT_ONLY } from "../model-def.js";
import { readModelsJson, writeModelsJson } from "../models-json.js";
import { stringParam } from "../params.js";
import { createRuntime } from "../runtime.js";
import { dropProvider, keepProviderInScope, replaceProviderModels, scopeResolver, scopeUniverse } from "../scope.js";
import { openSettingsIfValid, type PiSettings, saveEnabledModels } from "../settings.js";

/** The provider id Pipkin's on-device models are served under. */
export const LOCAL_PROVIDER = "pipkin-local";

/**
 * How pi-ai talks to Pipkin's local endpoint. `x-session-affinity` tells the app which chat a request belongs to, which
 * its queue keys model reservations on; the rest are what llama.cpp's server and Pipkin's LiteRT-LM bridge accept.
 */
const COMPAT = {
  sendSessionAffinityHeaders: true,
  sessionAffinityFormat: "openai-nosession",
  supportsDeveloperRole: false,
  supportsStore: false,
  supportsReasoningEffort: true,
  supportsUsageInStreaming: true,
  maxTokensField: "max_tokens",
  thinkingFormat: "openai",
};

const FREE = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };

/**
 * Pi's defaults reserve 16384 tokens for the reply and keep 20000 recent ones when compacting, more than a phone's
 * whole context. A quarter of the window each leaves room to work and to summarize with the same model.
 */
const COMPACTION_SHARE = 4;

/** Pi's settings manager members its public API has no setter for: per-model compaction overrides. */
interface CompactionWriter {
  globalSettings: { compaction?: { modelOverrides?: Record<string, unknown> } };
  markModified(field: string, nestedKey?: string): void;
  save(): void;
}

/**
 * Makes `models.json`'s `pipkin-local` provider list exactly the given models, served at `baseUrl` with `apiKey`, with
 * compaction overrides sized to each model's context; no models removes the provider. Keeps the models in scope when
 * settings.json `enabledModels` limits the model list.
 */
export async function localModelsSync(ctx: ModeContext): Promise<JsonObject> {
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const apiKey = stringParam(ctx.params, "apiKey");
  const models = modelDefsParam(ctx.params, "models");

  const document = await readModelsJson(ctx.env.configDir);
  const pi = await loadPi(ctx.env.root);
  const settings = openSettingsIfValid(ctx, pi);
  if (models.length === 0) {
    delete document.providers[LOCAL_PROVIDER];
  } else {
    document.providers[LOCAL_PROVIDER] = {
      baseUrl,
      api: "openai-completions",
      apiKey,
      compat: COMPAT,
      models: models.map((model) => ({ ...model, input: TEXT_ONLY, cost: FREE })),
    };
  }
  await writeModelsJson(ctx.env.configDir, document);
  if (settings === undefined) return {};

  await saveScope(ctx, pi, settings, models);
  await saveCompaction(settings, models);
  return {};
}

async function saveScope(ctx: ModeContext, pi: PiSdk, settings: PiSettings, models: readonly ModelDef[]): Promise<void> {
  if (settings.enabledModels.length === 0) return;
  if (models.length === 0) {
    const patterns = dropProvider(settings.enabledModels, LOCAL_PROVIDER);
    if (patterns.length !== settings.enabledModels.length) await saveEnabledModels(settings, patterns);
    return;
  }
  const runtime = await createRuntime(ctx);
  const saved = models.map((model) => ({ provider: LOCAL_PROVIDER, id: model.id, name: model.name ?? model.id }));
  const universe = replaceProviderModels(await scopeUniverse(runtime, ctx.signal), LOCAL_PROVIDER, saved);
  const patterns = await keepProviderInScope(
    settings.enabledModels,
    LOCAL_PROVIDER,
    settings.defaultRef,
    universe,
    scopeResolver(pi, universe),
  );
  if (patterns !== undefined) await saveEnabledModels(settings, patterns);
}

async function saveCompaction(settings: PiSettings, models: readonly ModelDef[]): Promise<void> {
  const writer = settings.manager as unknown as CompactionWriter;
  const compaction = (writer.globalSettings.compaction ??= {});
  const overrides: Record<string, unknown> = { ...compaction.modelOverrides };
  for (const key of Object.keys(overrides)) {
    if (key.startsWith(`${LOCAL_PROVIDER}/`)) delete overrides[key];
  }
  for (const model of models) {
    if (model.contextWindow === undefined) continue;
    const share = Math.floor(model.contextWindow / COMPACTION_SHARE);
    overrides[`${LOCAL_PROVIDER}/${model.id}`] = { reserveTokens: share, keepRecentTokens: share };
  }
  if (Object.keys(overrides).length > 0) compaction.modelOverrides = overrides;
  else delete compaction.modelOverrides;
  writer.markModified("compaction", "modelOverrides");
  writer.save();
  await settings.manager.flush();
  const [failure] = settings.manager.drainErrors();
  if (failure !== undefined) throw failure.error;
}
