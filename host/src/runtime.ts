import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { loadPi } from "./env.js";
import type { ModeContext } from "./mode.js";

/** Subscription providers Pipkin signs in to with OAuth. */
export const BUILTIN_PROVIDERS = ["anthropic", "openai-codex"] as const;

/**
 * Pi's model/auth runtime over `PI_CODING_AGENT_DIR`, without a model catalog refresh.
 * `builtinOnly` skips `models.json`, leaving only Pi's built-in providers and catalogue.
 */
export async function createRuntime(ctx: ModeContext, { builtinOnly = false } = {}): Promise<ModelRuntime> {
  const pi = await loadPi(ctx.env.root);
  return await pi.ModelRuntime.create({
    signal: ctx.signal,
    refreshOnCreate: false,
    ...(builtinOnly ? { modelsPath: null } : {}),
  });
}
