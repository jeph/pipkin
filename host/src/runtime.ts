import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { loadPi } from "./env.js";
import type { ModeContext } from "./mode.js";

/**
 * Pi's own providers Pipkin connects, and how: OAuth for the Claude and ChatGPT subscriptions, an
 * API key for Gemini, the Z.ai Coding Plan, Kimi Code and OpenRouter.
 */
const LOGIN_TYPES: Record<string, "oauth" | "api_key"> = {
  anthropic: "oauth",
  "openai-codex": "oauth",
  google: "api_key",
  zai: "api_key",
  "kimi-coding": "api_key",
  openrouter: "api_key",
};

export const BUILTIN_PROVIDERS: readonly string[] = Object.keys(LOGIN_TYPES);

/**
 * Where the providers Pipkin connects with an API key list the models their key may use, under their base
 * URL. Gemini's list has its own shape, so it has none here. These providers' models stay out of scope
 * after sign-in until the user picks some from the list.
 */
const MODEL_LIST_PATHS: Record<string, string> = { zai: "/models", "kimi-coding": "/models", openrouter: "/models/user" };

/** How Pipkin signs in to one of `BUILTIN_PROVIDERS`; undefined for any other provider. */
export function loginType(providerId: string): "oauth" | "api_key" | undefined {
  return Object.hasOwn(LOGIN_TYPES, providerId) ? LOGIN_TYPES[providerId] : undefined;
}

/** The path of the provider's list of models its key may use, or undefined when it has none. */
export function modelListPath(providerId: string): string | undefined {
  return Object.hasOwn(MODEL_LIST_PATHS, providerId) ? MODEL_LIST_PATHS[providerId] : undefined;
}

/** True for a provider whose models show only once the user picks them from its list. */
export function hiddenUntilPicked(providerId: string): boolean {
  return modelListPath(providerId) !== undefined;
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
