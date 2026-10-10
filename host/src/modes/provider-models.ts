import { HostError } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";

const TIMEOUT_MS = 15_000;
const BODY_PREVIEW = 300;

/** Lists an OpenAI-compatible endpoint's models so the user can pick instead of typing ids. */
export async function providerModels(ctx: ModeContext): Promise<JsonObject> {
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const apiKey = stringParam(ctx.params, "apiKey");
  let response: Response;
  try {
    response = await fetch(`${baseUrl.replace(/\/+$/, "")}/models`, {
      headers: { Authorization: `Bearer ${apiKey}` },
      signal: AbortSignal.any([ctx.signal, AbortSignal.timeout(TIMEOUT_MS)]),
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "TimeoutError") {
      throw new HostError("timeout", `No response from ${baseUrl} within 15 s`);
    }
    if (ctx.signal.aborted) throw error;
    const cause = error instanceof Error && error.cause instanceof Error ? `: ${error.cause.message}` : "";
    throw new HostError("network", `Could not reach ${baseUrl}${cause}`);
  }
  const body = await response.text();
  if (!response.ok) throw new HostError("http_error", `HTTP ${response.status}: ${body.slice(0, BODY_PREVIEW)}`);
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new HostError("bad_response", `The server did not return JSON: ${body.slice(0, BODY_PREVIEW)}`);
  }
  const data = isObject(parsed) ? parsed.data : undefined;
  if (!Array.isArray(data)) throw new HostError("bad_response", "The server's /models response has no data list");
  const models = data.flatMap((entry) => (isObject(entry) && typeof entry.id === "string" ? [entry.id] : []));
  return { models: models.toSorted() };
}
