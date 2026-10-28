import { loadCatalogue } from "../catalogue.js";
import { detectModel } from "../detect.js";
import { HostError } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { fillMissing, type ModelDef } from "../model-def.js";
import { NO_API_KEY } from "../models-json.js";
import { stringParam } from "../params.js";

const TIMEOUT_MS = 15_000;
const BODY_PREVIEW = 300;
/** Error pages and wrong base URLs answer with HTML, which says nothing useful as text. */
const HTML_BODY = /^\s*<(!doctype|html|head|body)/i;

/**
 * Lists an OpenAI-compatible endpoint's models with the capabilities it reports, filling the
 * fields it leaves out from Pi's built-in catalogue. Failures carry a code per cause (`bad_url`,
 * `network`, `timeout`, `unauthorized`, `not_found`, `rate_limited`, `server_error`, `http_error`,
 * `bad_response`) and a message with what the server said, for Pipkin to explain.
 */
export async function providerModels(ctx: ModeContext): Promise<JsonObject> {
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const apiKey = stringParam(ctx.params, "apiKey") || NO_API_KEY;
  const api = stringParam(ctx.params, "api");
  const protocol = URL.parse(baseUrl)?.protocol;
  if (protocol !== "http:" && protocol !== "https:") {
    throw new HostError("bad_url", `${baseUrl} is not an http:// or https:// URL`);
  }
  const url = `${baseUrl.replace(/\/+$/, "")}/models`;
  let response: Response;
  let body: string;
  // The body read shares the timeout and failure handling: a server can send headers and then stall or hang up.
  try {
    response = await fetch(url, {
      headers: { Authorization: `Bearer ${apiKey}` },
      signal: AbortSignal.any([ctx.signal, AbortSignal.timeout(TIMEOUT_MS)]),
    });
    body = await response.text();
  } catch (error) {
    if (error instanceof DOMException && error.name === "TimeoutError") {
      throw new HostError("timeout", `No response from ${baseUrl} within 15 s`);
    }
    if (ctx.signal.aborted) throw error;
    if (!(error instanceof Error && error.cause instanceof Error)) {
      throw new HostError("network", `Could not reach ${baseUrl}`, { key: "provider_unreachable", args: { url: baseUrl } });
    }
    const cause = error.cause.message;
    throw new HostError("network", `Could not reach ${baseUrl}: ${cause}`, {
      key: "provider_unreachable_because",
      args: { url: baseUrl, cause },
    });
  }
  if (!response.ok) {
    const code = httpErrorCode(response.status);
    const said = serverMessage(body);
    if (said === "") throw new HostError(code, `HTTP ${response.status}`, { key: "provider_http_status", args: { status: response.status } });
    throw new HostError(code, `HTTP ${response.status}: ${said}`, {
      key: "provider_http_status_said",
      args: { status: response.status, message: said },
    });
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    if (HTML_BODY.test(body)) {
      throw new HostError("bad_response", `${url} returned a web page, not JSON`, { key: "provider_web_page", args: { url } });
    }
    const preview = body.slice(0, BODY_PREVIEW);
    throw new HostError("bad_response", `The server did not return JSON: ${preview}`, {
      key: "provider_not_json",
      args: { body: preview },
    });
  }
  const data = isObject(parsed) ? parsed.data : undefined;
  if (!Array.isArray(data)) {
    throw new HostError("bad_response", "The server's /models response has no data list", { key: "provider_no_data_list" });
  }
  const detected = new Map<string, ModelDef>();
  for (const entry of data) {
    if (isObject(entry) && typeof entry.id === "string" && !detected.has(entry.id)) {
      detected.set(entry.id, detectModel(entry.id, entry));
    }
  }
  const catalogue = await loadCatalogue(ctx, api);
  const models = [...detected.values()].map((model) => fillMissing(model, catalogue(model.id)));
  return { models: models.toSorted((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) };
}

function httpErrorCode(status: number): string {
  if (status === 401 || status === 403) return "unauthorized";
  if (status === 404) return "not_found";
  if (status === 429) return "rate_limited";
  if (status >= 500) return "server_error";
  return "http_error";
}

/** The message in an error body: OpenAI's `{error: {message}}`, a bare `{error}` string or FastAPI's `{detail}`. */
function serverMessage(body: string): string {
  if (HTML_BODY.test(body)) return "";
  try {
    const parsed: unknown = JSON.parse(body);
    if (isObject(parsed)) {
      const error = parsed.error;
      const candidates = [isObject(error) ? error.message : error, parsed.message, parsed.detail];
      const message = candidates.find((value): value is string => typeof value === "string" && value.trim() !== "");
      if (message !== undefined) return message.trim().slice(0, BODY_PREVIEW);
    }
  } catch {
    // Not JSON: the body itself is the message.
  }
  return body.trim().slice(0, BODY_PREVIEW);
}
