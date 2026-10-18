import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { loadPi } from "./env.js";
import type { ModeContext } from "./mode.js";

/**
 * Pi's own providers Pipkin connects, and how: OAuth for the Claude and ChatGPT subscriptions, an
 * API key for Gemini, the Z.ai Coding Plan and Kimi Code.
 */
const LOGIN_TYPES: Record<string, "oauth" | "api_key"> = {
  anthropic: "oauth",
  "openai-codex": "oauth",
  google: "api_key",
  zai: "api_key",
  "kimi-coding": "api_key",
};

export const BUILTIN_PROVIDERS: readonly string[] = Object.keys(LOGIN_TYPES);

/** How Pipkin signs in to one of `BUILTIN_PROVIDERS`; undefined for any other provider. */
export function loginType(providerId: string): "oauth" | "api_key" | undefined {
  return Object.hasOwn(LOGIN_TYPES, providerId) ? LOGIN_TYPES[providerId] : undefined;
}

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
