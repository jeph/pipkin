import { isDeepStrictEqual } from "node:util";
import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import type { PiSdk } from "./env.js";
import { BUILTIN_PROVIDERS, hiddenUntilPicked } from "./runtime.js";

/** The parts of a model Pi's `enabledModels` matching looks at; `unusable` while its provider has no key. */
export type ScopeModel = { provider: string; id: string; name?: string; unusable?: true };
/** One model in scope, with the thinking level its pattern named (`provider/id:high`). */
export type ScopeRef = { provider: string; id: string; thinkingLevel?: string };
/** Matches patterns like Pipkin's scope; `usableOnly` like Pi at startup, against the models it can use. */
export type ScopeResolver = (patterns: readonly string[], options?: { usableOnly?: boolean }) => Promise<ScopeRef[]>;

const GLOB = /[*?[]/;

/**
 * The models Pi matches `enabledModels` against at startup (those it can use now), plus every model of
 * the providers Pipkin connects, marked `unusable`, so they can be scoped before sign-in. A provider
 * whose models the user picks from its list after sign-in counts only once signed in.
 */
export async function scopeUniverse(runtime: ModelRuntime, signal: AbortSignal): Promise<ScopeModel[]> {
  const available = new Set((await runtime.getAvailable(undefined, { signal })).map((model) => `${model.provider}/${model.id}`));
  return runtime.getModels().flatMap((model): ScopeModel[] => {
    if (available.has(`${model.provider}/${model.id}`)) return [model];
    if (!BUILTIN_PROVIDERS.includes(model.provider) || hiddenUntilPicked(model.provider)) return [];
    return [{ provider: model.provider, id: model.id, ...(model.name === undefined ? {} : { name: model.name }), unusable: true }];
  });
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
  const usable = universe.filter((model) => model.unusable !== true);
  return async (patterns, { usableOnly = false } = {}) => {
    // Pi's resolver only calls getAvailable(); this hands it Pipkin's model list instead of a runtime's.
    const source = { getAvailable: () => Promise.resolve(usableOnly ? usable : universe) } as unknown as ModelRuntime;
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
 * Rewrites `patterns` so that `provider` contributes exactly `enabled` (`"all"`: all its models) and
 * every other provider keeps the models it had. Empty `patterns` (all in scope) starts from all of
 * every provider's models. As no patterns would show every model, a result with none falls back to
 * the other providers' models.
 */
export async function rebuildForProvider(
  patterns: readonly string[],
  provider: string,
  enabled: readonly string[] | "all",
  universe: readonly ScopeModel[],
  resolve: ScopeResolver,
): Promise<string[]> {
  const start = patterns.length > 0 ? patterns : await everyProvider(universe, resolve);
  const rebuilt: string[] = [];
  for (const pattern of start) {
    if (ownedBy(pattern, provider)) continue;
    const matched = await resolve([pattern]);
    const others = matched.filter((ref) => ref.provider !== provider);
    if (others.length === matched.length) rebuilt.push(pattern);
    else rebuilt.push(...others.map(refPattern));
  }
  rebuilt.push(...(await providerPatterns(provider, enabled, universe, resolve)));
  const kept = await dropMisdirected(dedupe(rebuilt), universe, resolve);
  return kept.length > 0 ? kept : await othersFallback(provider, universe, resolve);
}

async function everyProvider(universe: readonly ScopeModel[], resolve: ScopeResolver): Promise<string[]> {
  const providers = [...new Set(universe.map((model) => model.provider))];
  const patterns = await Promise.all(providers.map((id) => providerPatterns(id, "all", universe, resolve)));
  return patterns.flat();
}

/** All models of every provider but `provider` and those hidden until picked, for a scope that must not be empty. */
async function othersFallback(provider: string | undefined, universe: readonly ScopeModel[], resolve: ScopeResolver): Promise<string[]> {
  const others = (await everyProvider(universe, resolve)).filter((pattern) => {
    const named = namedProvider(pattern);
    return named !== provider?.toLowerCase() && !hiddenUntilPicked(named);
  });
  return await dropMisdirected(others, universe, resolve);
}

function refPattern(ref: ScopeRef): string {
  return `${ref.provider}/${ref.id}${ref.thinkingLevel === undefined ? "" : `:${ref.thinkingLevel}`}`;
}

/** The provider a `provider/…` pattern names, lowercased; empty for a pattern without a slash. */
function namedProvider(pattern: string): string {
  return pattern.slice(0, Math.max(pattern.indexOf("/"), 0)).toLowerCase();
}

/**
 * Rewrites the patterns written for a provider (`p/…`) that Pi, matching only the models it can use,
 * would also resolve to another provider's, such as OpenRouter's `anthropic/…` copies. A provider with
 * a key keeps its own models by id, which Pi resolves to the named provider first. One without a key
 * loses all its patterns, as any of them would show the copies; signing in brings its models back.
 */
async function dropMisdirected(patterns: readonly string[], universe: readonly ScopeModel[], resolve: ScopeResolver): Promise<string[]> {
  const providers = new Set(universe.map((model) => model.provider.toLowerCase()));
  const usable = new Set(universe.filter((model) => model.unusable !== true).map((model) => model.provider.toLowerCase()));
  const signedOut = new Set<string>();
  const rewritten: string[] = [];
  for (const pattern of patterns) {
    const named = namedProvider(pattern);
    const refs = providers.has(named) ? await resolve([pattern], { usableOnly: true }) : [];
    if (refs.every((ref) => ref.provider.toLowerCase() === named)) rewritten.push(pattern);
    else if (usable.has(named)) rewritten.push(...refs.filter((ref) => ref.provider.toLowerCase() === named).map(refPattern));
    else signedOut.add(named);
  }
  return dedupe(rewritten.filter((pattern) => !signedOut.has(namedProvider(pattern))));
}

/**
 * `patterns` without the ones Pi would resolve to another provider's models (see `dropMisdirected`), for
 * a provider that just lost its key. As no patterns would show every model, OpenRouter's hundreds among
 * them, a scope left with none falls back to the other providers' models.
 */
export async function withoutMisdirected(patterns: readonly string[], universe: readonly ScopeModel[], resolve: ScopeResolver): Promise<string[]> {
  const kept = await dropMisdirected(patterns, universe, resolve);
  if (kept.length > 0 || patterns.length === 0) return kept;
  return await othersFallback(undefined, universe, resolve);
}

/**
 * The patterns that show `enabled` of `provider`'s models: one glob for all of them (`provider/**` when
 * ids hold a slash, like OpenRouter's, since `*` stops at one), unless it also matches another provider's
 * ids (OpenRouter's `anthropic/…`), then each id by name.
 */
async function providerPatterns(
  provider: string,
  enabled: readonly string[] | "all",
  universe: readonly ScopeModel[],
  resolve: ScopeResolver,
): Promise<string[]> {
  if (enabled !== "all") return enabled.map((id) => `${provider}/${id}`);
  const own = universe.filter((model) => model.provider === provider);
  const glob = own.some((model) => model.id.includes("/")) ? `${provider}/**` : `${provider}/*`;
  if ((await resolve([glob])).every((ref) => ref.provider === provider)) return [glob];
  return own.map((model) => `${provider}/${model.id}`);
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
 * of them gets all of them, so they show. Returns the new patterns, or undefined when nothing changes.
 */
export async function keepProviderInScope(
  patterns: readonly string[],
  provider: string,
  defaultRef: string | undefined,
  universe: readonly ScopeModel[],
  resolve: ScopeResolver,
): Promise<string[] | undefined> {
  if (patterns.length === 0) return undefined;
  if ((await resolve(patterns)).some((ref) => ref.provider === provider)) return undefined;
  const added = [...patterns, ...(await providerPatterns(provider, "all", universe, resolve))];
  return await putDefaultFirst(await dropMisdirected(added, universe, resolve), defaultRef, resolve);
}

/**
 * After sign-in to a provider hidden until picked, narrows the patterns that would show its models (an
 * empty scope, a glob, a fuzzy name) to the other providers' models, unless a pattern written for the
 * provider says what the user picked. Either way, other providers' patterns Pi would now resolve to this
 * provider's copies go. Returns the new patterns, or undefined when nothing changes.
 */
export async function hideUnpickedProvider(
  patterns: readonly string[],
  provider: string,
  defaultRef: string | undefined,
  universe: readonly ScopeModel[],
  resolve: ScopeResolver,
): Promise<string[] | undefined> {
  const picked = patterns.some((pattern) => ownedBy(pattern, provider));
  const narrowed = picked
    ? await withoutMisdirected(patterns, universe, resolve)
    : await rebuildForProvider(patterns, provider, [], universe, resolve);
  if (isDeepStrictEqual(narrowed, patterns)) return undefined;
  return await putDefaultFirst(narrowed, defaultRef, resolve);
}
