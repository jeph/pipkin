import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadPi, type PiSdk } from "../src/env.js";
import {
  keepProviderInScope,
  NO_MODELS,
  putDefaultFirst,
  type ScopeModel,
  type ScopeResolver,
  scopeResolver,
  withoutMisdirected,
} from "../src/scope.js";
import { type Dirs, installRealPi, makeDirs, removeDirs } from "./harness.js";

const UNIVERSE = [
  { provider: "anthropic", id: "claude-opus-4-8", name: "Claude Opus 4.8" },
  { provider: "anthropic", id: "claude-sonnet-5", name: "Claude Sonnet 5" },
  { provider: "openai-codex", id: "gpt-5.5", name: "GPT-5.5" },
  { provider: "my-lb", id: "lb-1", name: "lb-1" },
];

/** The rule login (and provider_save) applies once a provider has models to show. */
describe("keeping a provider in scope", () => {
  let dirs: Dirs;
  let resolve: ScopeResolver;

  beforeAll(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    resolve = scopeResolver(await loadPi(dirs.root), UNIVERSE);
  });

  afterAll(async () => {
    await removeDirs(dirs);
  });

  it("appends provider/* when no pattern matches the provider", async () => {
    expect(await keepProviderInScope(["my-lb/*"], "anthropic", undefined, UNIVERSE, resolve)).toEqual(["my-lb/*", "anthropic/*"]);
  });

  it("leaves an empty scope or one that already matches alone", async () => {
    expect(await keepProviderInScope([], "anthropic", undefined, UNIVERSE, resolve)).toBeUndefined();
    expect(await keepProviderInScope(["sonnet"], "anthropic", undefined, UNIVERSE, resolve)).toBeUndefined();
  });

  it("replaces the no-models placeholder once a provider gains models", async () => {
    expect(await keepProviderInScope([NO_MODELS], "anthropic", undefined, UNIVERSE, resolve)).toEqual(["anthropic/*"]);
    expect(await resolve([NO_MODELS])).toEqual([]);
  });

  it("moves the default model's explicit entry first", async () => {
    expect(
      await keepProviderInScope(["my-lb/*", "openai-codex/gpt-5.5:high"], "anthropic", "openai-codex/gpt-5.5", UNIVERSE, resolve),
    ).toEqual(["openai-codex/gpt-5.5:high", "my-lb/*", "anthropic/*"]);
  });

  it("adds the default model first when only a glob covers it", async () => {
    expect(await keepProviderInScope(["my-lb/*"], "anthropic", "anthropic/claude-sonnet-5", UNIVERSE, resolve)).toEqual([
      "anthropic/claude-sonnet-5",
      "my-lb/*",
      "anthropic/*",
    ]);
  });

  it("leaves the order alone when the default model is out of scope", async () => {
    expect(await putDefaultFirst(["my-lb/*"], "openai-codex/gpt-5.5", resolve)).toEqual(["my-lb/*"]);
  });
});

/** OpenRouter names its copies of other providers' models `vendor/id`, which Pi also reads as an id. */
describe("scope with OpenRouter's copies of other providers' models", () => {
  const COPIES: ScopeModel[] = [
    { provider: "openrouter", id: "anthropic/claude-opus-4-8", name: "Anthropic: Claude Opus 4.8" },
    { provider: "openrouter", id: "google/gemma-4", name: "Google: Gemma 4" },
  ];
  const SIGNED_IN = [...UNIVERSE, ...COPIES];
  const SIGNED_OUT: ScopeModel[] = [...UNIVERSE.map((model) => (model.provider === "anthropic" ? { ...model, unusable: true as const } : model)), ...COPIES];
  let dirs: Dirs;
  let pi: PiSdk;

  beforeAll(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    pi = await loadPi(dirs.root);
  });

  afterAll(async () => {
    await removeDirs(dirs);
  });

  it("names a signed-in provider's models instead of a glob that would also show the copies", async () => {
    const resolve = scopeResolver(pi, SIGNED_IN);
    expect(await keepProviderInScope(["my-lb/*"], "anthropic", undefined, SIGNED_IN, resolve)).toEqual([
      "my-lb/*",
      "anthropic/claude-opus-4-8",
      "anthropic/claude-sonnet-5",
    ]);
    expect(await withoutMisdirected(["anthropic/*", "my-lb/*"], SIGNED_IN, resolve)).toEqual([
      "anthropic/claude-opus-4-8",
      "anthropic/claude-sonnet-5",
      "my-lb/*",
    ]);
  });

  it("drops every pattern of a provider without a key, which Pi would resolve to the copies", async () => {
    const resolve = scopeResolver(pi, SIGNED_OUT);
    const patterns = await withoutMisdirected(["anthropic/claude-opus-4-8", "anthropic/claude-sonnet-5", "my-lb/*"], SIGNED_OUT, resolve);
    expect(patterns).toEqual(["my-lb/*"]);
    expect(await resolve(patterns, { usableOnly: true })).toEqual([{ provider: "my-lb", id: "lb-1" }]);
  });

  it("falls back to the other providers' models rather than an empty scope that would show every copy", async () => {
    const resolve = scopeResolver(pi, SIGNED_OUT);
    const patterns = await withoutMisdirected(["anthropic/*"], SIGNED_OUT, resolve);
    expect(await resolve(patterns, { usableOnly: true })).toEqual([
      { provider: "openai-codex", id: "gpt-5.5" },
      { provider: "my-lb", id: "lb-1" },
    ]);
  });
});
