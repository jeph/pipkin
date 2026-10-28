import { isObject, type JsonObject } from "./framing.js";
import {
  type ModelCost,
  type ModelDef,
  positiveNumber,
  TEXT_AND_IMAGE,
  TEXT_ONLY,
  THINKING_LEVELS,
  type ThinkingLevelMap,
} from "./model-def.js";

const PER_MILLION = 1_000_000;

/** `entry.a.b`, or undefined when any step is missing. */
function at(entry: JsonObject, path: string): unknown {
  let value: unknown = entry;
  for (const key of path.split(".")) {
    if (!isObject(value)) return undefined;
    value = value[key];
  }
  return value;
}

function firstPositive(entry: JsonObject, paths: readonly string[]): number | undefined {
  for (const path of paths) {
    const value = positiveNumber(at(entry, path));
    if (value !== undefined) return value;
  }
  return undefined;
}

/** True if any source says yes, false if one says no and none says yes, undefined if none says. */
function anyOf(answers: readonly (boolean | undefined)[]): boolean | undefined {
  return answers.includes(true) ? true : answers.includes(false) ? false : undefined;
}

function flag(entry: JsonObject, path: string): boolean | undefined {
  const value = at(entry, path);
  return typeof value === "boolean" ? value : undefined;
}

function listContains(entry: JsonObject, path: string, item: string): boolean | undefined {
  const value = at(entry, path);
  return Array.isArray(value) ? value.includes(item) : undefined;
}

/** Reasoning efforts the entry lists: codex-lb's `metadata.supported_reasoning_levels[].effort`, or Kimi's `reasoning.effort.valid`. */
function efforts(entry: JsonObject): Set<string> | undefined {
  const levels = at(entry, "metadata.supported_reasoning_levels");
  if (Array.isArray(levels)) {
    return new Set(levels.flatMap((level) => (isObject(level) && typeof level.effort === "string" ? [level.effort] : [])));
  }
  const valid = at(entry, "reasoning.effort.valid");
  if (Array.isArray(valid)) return new Set(valid.filter((effort): effort is string => typeof effort === "string"));
  return undefined;
}

/** The listed efforts as Pi levels; efforts Pi has no level for are dropped, and a list with none Pi knows is ignored. */
function thinkingLevelMap(entry: JsonObject): ThinkingLevelMap | undefined {
  const listed = efforts(entry);
  if (listed === undefined || !["none", ...THINKING_LEVELS].some((level) => listed.has(level))) return undefined;
  const map: ThinkingLevelMap = {};
  for (const level of THINKING_LEVELS) {
    map[level] = level === "off" ? (listed.has("none") ? "none" : null) : listed.has(level) ? level : null;
  }
  return map;
}

/** OpenRouter `pricing` strings are USD per token; Pi wants USD per million tokens. */
function pricing(entry: JsonObject, key: string): number | undefined {
  const value = at(entry, `pricing.${key}`);
  const perToken = typeof value === "string" && value.trim() !== "" ? Number(value) : value;
  if (typeof perToken !== "number" || !Number.isFinite(perToken) || perToken < 0) return undefined;
  // toPrecision drops float noise such as 0.000003 * 1e6 = 2.9999999999999996.
  return Number((perToken * PER_MILLION).toPrecision(12));
}

function cost(entry: JsonObject): ModelCost | undefined {
  const input = pricing(entry, "prompt");
  const output = pricing(entry, "completion");
  if (input === undefined || output === undefined) return undefined;
  return { input, output, cacheRead: pricing(entry, "input_cache_read") ?? 0, cacheWrite: pricing(entry, "input_cache_write") ?? 0 };
}

/** Reads the capabilities an OpenAI-compatible `/models` entry reports (codex-lb, OpenRouter, Kimi, LiteLLM-style). */
export function detectModel(id: string, entry: JsonObject): ModelDef {
  const def: ModelDef = { id };

  const name = [at(entry, "metadata.display_name"), entry.display_name, entry.name].find((value) => typeof value === "string" && value !== "");
  if (typeof name === "string") def.name = name;

  const levels = thinkingLevelMap(entry);
  const reasoning =
    levels === undefined
      ? anyOf([
          flag(entry, "supports_reasoning"),
          flag(entry, "capabilities.supports_reasoning"),
          listContains(entry, "supported_parameters", "reasoning"),
        ])
      : true;
  if (reasoning !== undefined) def.reasoning = reasoning;
  if (levels !== undefined) def.thinkingLevelMap = levels;

  const images = anyOf([
    flag(entry, "supports_images"),
    flag(entry, "supports_vision"),
    flag(entry, "supports_image_in"),
    flag(entry, "capabilities.supports_images"),
    ...["input_modalities", "metadata.input_modalities", "capabilities.input_modalities", "architecture.input_modalities", "modalities.input"].map(
      (path) => listContains(entry, path, "image"),
    ),
  ]);
  if (images !== undefined) def.input = images ? TEXT_AND_IMAGE : TEXT_ONLY;

  const contextWindow = firstPositive(entry, [
    "context_length",
    "context_window",
    "contextLength",
    "metadata.context_window",
    "capabilities.context_length",
  ]);
  if (contextWindow !== undefined) def.contextWindow = contextWindow;

  const maxTokens = firstPositive(entry, [
    "max_output_tokens",
    "maxOutputTokens",
    "metadata.max_output_tokens",
    "top_provider.max_completion_tokens",
  ]);
  if (maxTokens !== undefined) def.maxTokens = maxTokens;

  const rates = cost(entry);
  if (rates !== undefined) def.cost = rates;
  return def;
}
