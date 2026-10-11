import { HostError } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";

/** Pi's thinking levels, in the order Pi lists them. */
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];
/** Pi level → provider effort value; `null` means Pi does not offer that level. */
export type ThinkingLevelMap = Partial<Record<ThinkingLevel, string | null>>;
export type ModelInput = ["text"] | ["text", "image"];
/** USD per million tokens, as in Pi's `models.json`. */
export type ModelCost = { input: number; output: number; cacheRead: number; cacheWrite: number };

/** The per-model settings Pipkin reads and writes; a field is absent when unknown. */
export type ModelDef = {
  id: string;
  name?: string;
  reasoning?: boolean;
  thinkingLevelMap?: ThinkingLevelMap;
  input?: ModelInput;
  contextWindow?: number;
  maxTokens?: number;
  cost?: ModelCost;
};

export type ManagedField = Exclude<keyof ModelDef, "id">;
export const MANAGED_FIELDS: readonly ManagedField[] = [
  "name",
  "reasoning",
  "thinkingLevelMap",
  "input",
  "contextWindow",
  "maxTokens",
  "cost",
];

export const TEXT_ONLY: ModelInput = ["text"];
export const TEXT_AND_IMAGE: ModelInput = ["text", "image"];

export function positiveNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : undefined;
}

function rate(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

function parseThinkingLevelMap(value: unknown): ThinkingLevelMap | undefined {
  if (!isObject(value)) return undefined;
  const map: ThinkingLevelMap = {};
  for (const [key, level] of Object.entries(value)) {
    if (!(THINKING_LEVELS as readonly string[]).includes(key)) return undefined;
    if (level !== null && typeof level !== "string") return undefined;
    map[key as ThinkingLevel] = level;
  }
  return map;
}

function parseInput(value: unknown): ModelInput | undefined {
  if (!Array.isArray(value) || value.length === 0) return undefined;
  if (!value.every((item) => item === "text" || item === "image")) return undefined;
  return value.includes("image") ? TEXT_AND_IMAGE : TEXT_ONLY;
}

/** The four rates only: Pi's `tiers` are not edited by Pipkin. */
function parseCost(value: unknown): ModelCost | undefined {
  if (!isObject(value)) return undefined;
  const input = rate(value.input);
  const output = rate(value.output);
  const cacheRead = rate(value.cacheRead);
  const cacheWrite = rate(value.cacheWrite);
  if (input === undefined || output === undefined || cacheRead === undefined || cacheWrite === undefined) return undefined;
  return { input, output, cacheRead, cacheWrite };
}

const PARSERS: { [K in ManagedField]-?: (value: unknown) => ModelDef[K] | undefined } = {
  name: (value) => (typeof value === "string" && value !== "" ? value : undefined),
  reasoning: (value) => (typeof value === "boolean" ? value : undefined),
  thinkingLevelMap: parseThinkingLevelMap,
  input: parseInput,
  contextWindow: positiveNumber,
  maxTokens: positiveNumber,
  cost: parseCost,
};

/**
 * Reads the managed fields of a model object. `null` or missing means absent. A wrongly typed field
 * is skipped, or reported through `onInvalid` (which may throw).
 */
export function parseModelDef(id: string, source: JsonObject, onInvalid?: (field: ManagedField) => void): ModelDef {
  const def: ModelDef = { id };
  for (const field of MANAGED_FIELDS) {
    const raw = source[field];
    if (raw === undefined || raw === null) continue;
    const value = PARSERS[field](raw);
    if (value === undefined) onInvalid?.(field);
    else (def as Record<string, unknown>)[field] = value;
  }
  return def;
}

/** Fills every managed field missing from `primary` with the value from `fallback`. */
export function fillMissing(primary: ModelDef, fallback: ModelDef | undefined): ModelDef {
  if (fallback === undefined) return primary;
  const merged: ModelDef = { ...primary };
  for (const field of MANAGED_FIELDS) {
    if (merged[field] === undefined && fallback[field] !== undefined) {
      (merged as Record<string, unknown>)[field] = fallback[field];
    }
  }
  return merged;
}

/** Parses a `ModelDef[]` request parameter strictly: bad ids, duplicates and bad field types are `bad_params`. */
export function modelDefsParam(params: JsonObject, key: string): ModelDef[] {
  const value = params[key];
  if (!Array.isArray(value)) throw new HostError("bad_params", `Parameter "${key}" must be an array of models`);
  const ids = new Set<string>();
  return value.map((model) => {
    if (!isObject(model) || typeof model.id !== "string" || model.id === "") {
      throw new HostError("bad_params", "Every model needs a non-empty string id");
    }
    const id = model.id;
    if (ids.has(id)) throw new HostError("bad_params", `Model "${id}" is listed twice`);
    ids.add(id);
    return parseModelDef(id, model, (field) => {
      throw new HostError("bad_params", `Model "${id}" has an invalid ${field}`);
    });
  });
}

/** The object entries of a `models.json` model list, by id. */
export function objectsById(list: unknown): Map<string, JsonObject> {
  const byId = new Map<string, JsonObject>();
  for (const model of Array.isArray(list) ? list : []) {
    if (isObject(model) && typeof model.id === "string") byId.set(model.id, model);
  }
  return byId;
}

/** A copy of a stored model or override without the fields Pipkin owns. */
export function withoutManagedFields(stored: JsonObject | undefined): JsonObject {
  const kept: JsonObject = { ...stored };
  for (const field of MANAGED_FIELDS) delete kept[field];
  return kept;
}

/** The stored `cost.tiers`, which Pipkin never edits but keeps whenever it writes a cost. */
export function storedCostTiers(stored: JsonObject | undefined): unknown[] | undefined {
  return isObject(stored?.cost) && Array.isArray(stored.cost.tiers) ? stored.cost.tiers : undefined;
}

/**
 * Pipkin owns the managed fields; everything else the agent or user put on the model
 * (headers, compat, samplingParams, cost tiers, …) is kept.
 */
export function mergeManagedFields(def: ModelDef, existing: JsonObject | undefined): JsonObject {
  const merged: JsonObject = { ...withoutManagedFields(existing), ...def };
  const tiers = storedCostTiers(existing);
  if (def.cost !== undefined && tiers !== undefined) merged.cost = { ...def.cost, tiers };
  return merged;
}

