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
 * pi-ai caps each request's `max_tokens` at the context window less the prompt and this margin
 * (`CONTEXT_SAFETY_TOKENS` in its `clampMaxTokensToContext`), so a reply only gets its whole `maxTokens` while the
 * prompt leaves both free.
 */
const PI_AI_CONTEXT_MARGIN = 4096;

/**
 * Pi's defaults reserve 16384 tokens for the reply and keep 20000 recent ones when compacting, more than a phone's
 * whole context. Compacting once the reply and pi-ai's margin no longer fit means a reply is never cut short before
 * then. What compaction keeps has to fit in what's left of the context after Pi's system prompt and tools, about
 * 7,000 tokens, and the summary, or compacting frees nothing: a sixteenth of the context, 1,024 tokens of 16,384.
 */
const KEPT_SHARE = 16;

/** Pi's settings manager members its public API has no setter for: per-model compaction overrides. */
interface CompactionWriter {
  globalSettings: { compaction?: { modelOverrides?: Record<string, unknown> } };
  markModified(field: string, nestedKey?: string): void;
  save(): void;
}

/**
 * Makes `models.json`'s `pipkin-local` provider list exactly the given models, served at `baseUrl` with `apiKey`, with
 * compaction overrides sized to each model's context; no models removes the provider. Keeps the models in scope when
 * settings.json `enabledModels` limits the model list. Writes only what differs and answers `changed` when it wrote
 * anything, since the app syncs on every start and restarts idle chats only after a change.
 */
export async function localModelsSync(ctx: ModeContext): Promise<JsonObject> {
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const apiKey = stringParam(ctx.params, "apiKey");
  const models = modelDefsParam(ctx.params, "models");

  const document = await readModelsJson(ctx.env.configDir);
  const pi = await loadPi(ctx.env.root);
  const settings = openSettingsIfValid(ctx, pi);
  const before = JSON.stringify(document.providers[LOCAL_PROVIDER]);
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
  const providerChanged = JSON.stringify(document.providers[LOCAL_PROVIDER]) !== before;
  if (providerChanged) await writeModelsJson(ctx.env.configDir, document);
  if (settings === undefined) return { changed: providerChanged };

  const scopeChanged = await saveScope(ctx, pi, settings, models);
  const compactionChanged = await saveCompaction(settings, models);
  return { changed: providerChanged || scopeChanged || compactionChanged };
}

/** Whether it saved new `enabledModels`. */
async function saveScope(ctx: ModeContext, pi: PiSdk, settings: PiSettings, models: readonly ModelDef[]): Promise<boolean> {
  if (settings.enabledModels.length === 0) return false;
  if (models.length === 0) {
    const patterns = dropProvider(settings.enabledModels, LOCAL_PROVIDER);
    if (patterns.length === settings.enabledModels.length) return false;
    await saveEnabledModels(settings, patterns);
    return true;
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
  if (patterns === undefined) return false;
  await saveEnabledModels(settings, patterns);
  return true;
}

/** Whether it saved new compaction overrides. */
async function saveCompaction(settings: PiSettings, models: readonly ModelDef[]): Promise<boolean> {
  const writer = settings.manager as unknown as CompactionWriter;
  const current = writer.globalSettings.compaction?.modelOverrides;
  const overrides = Object.fromEntries(
    Object.entries(current ?? {}).filter(([key]) => !key.startsWith(`${LOCAL_PROVIDER}/`)),
  );
  for (const model of models) {
    if (model.contextWindow === undefined || model.maxTokens === undefined) continue;
    overrides[`${LOCAL_PROVIDER}/${model.id}`] = {
      reserveTokens: model.maxTokens + PI_AI_CONTEXT_MARGIN,
      keepRecentTokens: Math.floor(model.contextWindow / KEPT_SHARE),
    };
  }
  const next = Object.keys(overrides).length > 0 ? overrides : undefined;
  if (JSON.stringify(next) === JSON.stringify(current)) return false;

  const compaction = (writer.globalSettings.compaction ??= {});
  if (next === undefined) delete compaction.modelOverrides;
  else compaction.modelOverrides = next;
  writer.markModified("compaction", "modelOverrides");
  writer.save();
  await settings.manager.flush();
  const [failure] = settings.manager.drainErrors();
  if (failure !== undefined) throw failure.error;
  return true;
}
