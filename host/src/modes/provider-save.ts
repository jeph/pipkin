import { HostError } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { readModelsJson, writeModelsJson } from "../models-json.js";
import { booleanParam, stringArrayParam, stringParam } from "../params.js";
import { createRuntime } from "../runtime.js";

const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,39}$/;
const APIS = ["openai-completions", "openai-responses"];

/** Adds or replaces one OpenAI-compatible provider in `models.json`, keeping everything else. */
export async function providerSave(ctx: ModeContext): Promise<JsonObject> {
  const id = stringParam(ctx.params, "id");
  const baseUrl = stringParam(ctx.params, "baseUrl");
  const api = stringParam(ctx.params, "api");
  const apiKeyParam = stringParam(ctx.params, "apiKey");
  const images = booleanParam(ctx.params, "images");
  const models = stringArrayParam(ctx.params, "models");
  if (!PROVIDER_ID.test(id)) {
    throw new HostError("bad_id", "Use 1–40 lowercase letters, digits or dashes, starting with a letter or digit");
  }
  if (!APIS.includes(api)) throw new HostError("bad_params", `Unsupported API type ${api}`);

  const document = await readModelsJson(ctx.env.configDir);
  const existing = document.providers[id];
  if (existing === undefined) {
    const runtime = await createRuntime(ctx);
    if (runtime.getProviders().some((provider) => provider.id === id)) {
      throw new HostError("builtin_id", `"${id}" is a built-in Pi provider; choose another ID`);
    }
  }
  // An empty key means "keep the stored one", so editing never needs the secret again.
  const storedKey = isObject(existing) && typeof existing.apiKey === "string" ? existing.apiKey : undefined;
  const apiKey = apiKeyParam === "" ? storedKey : apiKeyParam;
  if (apiKey === undefined || apiKey === "") throw new HostError("bad_params", "An API key is required");

  document.providers[id] = {
    baseUrl,
    api,
    apiKey,
    models: models.map((model) => (images ? { id: model, input: ["text", "image"] } : { id: model })),
  };
  await writeModelsJson(ctx.env.configDir, document);
  return {};
}
