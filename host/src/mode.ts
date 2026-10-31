import type { Connection } from "./connection.js";
import { HostError, type HostEnv } from "./env.js";
import type { JsonObject } from "./framing.js";

export interface ModeContext {
  env: HostEnv;
  params: JsonObject;
  conn: Connection;
  /** Aborts when the app closes the socket. */
  signal: AbortSignal;
}

/** The `pipkin_result` that reports `error` as a refusal, under its code when it is a [HostError]. */
export function failure(error: unknown): JsonObject {
  if (error instanceof HostError) return { type: "pipkin_result", ok: false, error: error.message, code: error.code, i18n: error.i18n };
  return { type: "pipkin_result", ok: false, error: error instanceof Error ? error.message : String(error), code: "failed" };
}
