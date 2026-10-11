import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadPi } from "../src/env.js";
import { keepProviderInScope, putDefaultFirst, type ScopeResolver, scopeResolver } from "../src/scope.js";
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
    expect(await keepProviderInScope(["my-lb/*"], "anthropic", undefined, resolve)).toEqual(["my-lb/*", "anthropic/*"]);
  });

  it("leaves an empty scope or one that already matches alone", async () => {
    expect(await keepProviderInScope([], "anthropic", undefined, resolve)).toBeUndefined();
    expect(await keepProviderInScope(["sonnet"], "anthropic", undefined, resolve)).toBeUndefined();
  });

  it("moves the default model's explicit entry first", async () => {
    expect(await keepProviderInScope(["my-lb/*", "openai-codex/gpt-5.5:high"], "anthropic", "openai-codex/gpt-5.5", resolve)).toEqual([
      "openai-codex/gpt-5.5:high",
      "my-lb/*",
      "anthropic/*",
    ]);
  });

  it("adds the default model first when only a glob covers it", async () => {
    expect(await keepProviderInScope(["my-lb/*"], "anthropic", "anthropic/claude-sonnet-5", resolve)).toEqual([
      "anthropic/claude-sonnet-5",
      "my-lb/*",
      "anthropic/*",
    ]);
  });

  it("leaves the order alone when the default model is out of scope", async () => {
    expect(await putDefaultFirst(["my-lb/*"], "openai-codex/gpt-5.5", resolve)).toEqual(["my-lb/*"]);
  });
});
