import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import type { PiSdk } from "./env.js";
import { BUILTIN_PROVIDERS } from "./runtime.js";

/** The parts of a model Pi's `enabledModels` matching looks at. */
export type ScopeModel = { provider: string; id: string; name?: string };
/** One model in scope, with the thinking level its pattern named (`provider/id:high`). */
export type ScopeRef = { provider: string; id: string; thinkingLevel?: string };
export type ScopeResolver = (patterns: readonly string[]) => Promise<ScopeRef[]>;

const GLOB = /[*?[]/;

/**
 * The models Pi matches `enabledModels` against at startup (those it can use now), plus every
 * subscription model so they can be scoped before sign-in.
 */
export async function scopeUniverse(runtime: ModelRuntime, signal: AbortSignal): Promise<ScopeModel[]> {
  const available = new Set((await runtime.getAvailable(undefined, { signal })).map((model) => `${model.provider}/${model.id}`));
  return runtime
    .getModels()
    .filter((model) => available.has(`${model.provider}/${model.id}`) || (BUILTIN_PROVIDERS as readonly string[]).includes(model.provider));
}

/** `universe` with `provider`'s models replaced, e.g. by the list a save is about to write. */
export function replaceProviderModels(universe: readonly ScopeModel[], provider: string, models: readonly ScopeModel[]): ScopeModel[] {
  // Every model before the provider's first one belongs to another provider, so that index survives the filter.
  const index = universe.findIndex((model) => model.provider === provider);
  const others = universe.filter((model) => model.provider !== provider);
  return others.toSpliced(index === -1 ? others.length : index, 0, ...models);
}

/** Matches patterns with Pi's own resolver (globs, bare ids, fuzzy names, `:level` suffixes). */
export function scopeResolver(pi: PiSdk, universe: readonly ScopeModel[]): ScopeResolver {
  // Pi's resolver only calls getAvailable(); this hands it Pipkin's model list instead of a runtime's.
  const source = { getAvailable: () => Promise.resolve(universe) } as unknown as ModelRuntime;
  return async (patterns) => {
    const { scopedModels } = await pi.resolveModelScopeWithDiagnostics([...patterns], source);
    return scopedModels.map(({ model, thinkingLevel }) =>
      thinkingLevel === undefined ? { provider: model.provider, id: model.id } : { provider: model.provider, id: model.id, thinkingLevel },
    );
  };
}

/** A pattern written as `provider/…`, which Pipkin treats as belonging to that provider. */
function ownedBy(pattern: string, provider: string): boolean {
  return pattern.toLowerCase().startsWith(`${provider.toLowerCase()}/`);
}

function dedupe(patterns: readonly string[]): string[] {
  const seen = new Set<string>();
  return patterns.filter((pattern) => {
    const key = pattern.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/**
 * Rewrites `patterns` so that `provider` contributes exactly `enabled` ("all" = `provider/*`) and every
 * other provider keeps the models it had. Empty `patterns` (all in scope) starts from `p/*` per provider.
 */
export async function rebuildForProvider(
  patterns: readonly string[],
  provider: string,
  enabled: readonly string[] | "all",
  universe: readonly ScopeModel[],
  resolve: ScopeResolver,
): Promise<string[]> {
  const start = patterns.length > 0 ? patterns : [...new Set(universe.map((model) => model.provider))].map((id) => `${id}/*`);
  const rebuilt: string[] = [];
  for (const pattern of start) {
    if (ownedBy(pattern, provider)) continue;
    const matched = await resolve([pattern]);
    const others = matched.filter((ref) => ref.provider !== provider);
    if (others.length === matched.length) rebuilt.push(pattern);
    else rebuilt.push(...others.map((ref) => `${ref.provider}/${ref.id}${ref.thinkingLevel === undefined ? "" : `:${ref.thinkingLevel}`}`));
  }
  rebuilt.push(...(enabled === "all" ? [`${provider}/*`] : enabled.map((id) => `${provider}/${id}`)));
  return dedupe(rebuilt);
}

/** Drops the patterns written for a removed provider. */
export function dropProvider(patterns: readonly string[], provider: string): string[] {
  return patterns.filter((pattern) => !ownedBy(pattern, provider));
}

/**
 * Pi starts every new session on the first scoped model, ahead of the saved default, so the default
 * goes first as an explicit `provider/id` (moving an existing exact entry, `:level` included).
 */
export async function putDefaultFirst(patterns: readonly string[], defaultRef: string | undefined, resolve: ScopeResolver): Promise<string[]> {
  if (defaultRef === undefined || patterns.length === 0) return [...patterns];
  const target = defaultRef.toLowerCase();
  const scoped = await resolve(patterns);
  if (!scoped.some((ref) => `${ref.provider}/${ref.id}`.toLowerCase() === target)) return [...patterns];
  const index = patterns.findIndex((pattern) => {
    const lower = pattern.toLowerCase();
    return !GLOB.test(pattern) && (lower === target || lower.startsWith(`${target}:`));
  });
  if (index === -1) return [defaultRef, ...patterns];
  return [...patterns.slice(index, index + 1), ...patterns.toSpliced(index, 1)];
}

/**
 * After `provider` gains models (sign-in, a saved custom provider), a non-empty scope that matches none
 * of them gets `provider/*`, so they show. Returns the new patterns, or undefined when nothing changes.
 */
export async function keepProviderInScope(
  patterns: readonly string[],
  provider: string,
  defaultRef: string | undefined,
  resolve: ScopeResolver,
): Promise<string[] | undefined> {
  if (patterns.length === 0) return undefined;
  if ((await resolve(patterns)).some((ref) => ref.provider === provider)) return undefined;
  return await putDefaultFirst([...patterns, `${provider}/*`], defaultRef, resolve);
}
