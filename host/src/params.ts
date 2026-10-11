import { HostError } from "./env.js";
import type { JsonObject } from "./framing.js";

export function stringParam(params: JsonObject, key: string): string {
  const value = params[key];
  if (typeof value !== "string") throw new HostError("bad_params", `Missing string parameter "${key}"`);
  return value;
}

export function nullableStringParam(params: JsonObject, key: string): string | null {
  const value = params[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") throw new HostError("bad_params", `Parameter "${key}" must be a string or null`);
  return value;
}

export function stringArrayParam(params: JsonObject, key: string): string[] {
  const value = params[key];
  if (!Array.isArray(value) || !value.every((item): item is string => typeof item === "string")) {
    throw new HostError("bad_params", `Parameter "${key}" must be an array of strings`);
  }
  return value;
}
