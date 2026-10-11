import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { readModelsJson } from "../models-json.js";
import { createRuntime } from "../runtime.js";

/** Subscription providers Pipkin signs in to with OAuth. */
export const BUILTIN_PROVIDERS = ["anthropic", "openai-codex"] as const;

export async function authStatus(ctx: ModeContext): Promise<JsonObject> {
  const runtime = await createRuntime(ctx);
  const builtin: JsonObject[] = [];
  for (const id of BUILTIN_PROVIDERS) {
    const check = await runtime.checkAuth(id, { signal: ctx.signal });
    builtin.push({
      id,
      name: runtime.getProvider(id)?.name ?? id,
      configured: check !== undefined,
      type: check?.type ?? null,
    });
  }

  const custom: JsonObject[] = [];
  let modelsError = runtime.getError();
  try {
    const { providers } = await readModelsJson(ctx.env.configDir);
    for (const [id, provider] of Object.entries(providers)) {
      if (!isObject(provider)) continue;
      const models = Array.isArray(provider.models) ? provider.models : [];
      custom.push({
        id,
        baseUrl: provider.baseUrl,
        api: provider.api,
        // The editor shows and reuses the stored key, so editing never asks for it again.
        apiKey: typeof provider.apiKey === "string" ? provider.apiKey : "",
        models: models.flatMap((model) => (isObject(model) && typeof model.id === "string" ? [model.id] : [])),
        // Lets the provider editor keep image input when re-saving; provider_save sets it on every model.
        images: models.some((model) => isObject(model) && Array.isArray(model.input) && model.input.includes("image")),
      });
    }
  } catch (error) {
    modelsError = error instanceof Error ? error.message : String(error);
  }
  return modelsError === undefined ? { builtin, custom } : { builtin, custom, modelsError };
}
