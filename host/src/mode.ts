import type { Connection } from "./connection.js";
import type { HostEnv } from "./env.js";
import type { JsonObject } from "./framing.js";

export interface ModeContext {
  env: HostEnv;
  params: JsonObject;
  conn: Connection;
  /** Aborts when the app closes the socket. */
  signal: AbortSignal;
}
