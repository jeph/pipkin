import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { HostError } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";

export const MODELS_INVALID = "models.json is not valid JSON. Ask the agent to fix it.";

/**
 * The `apiKey` Pipkin stores for an endpoint that needs none, such as a local server. Pi requires a non-empty key
 * for a `models.json` provider and sends it in chat, so a keyless provider gets this one, as Pi's docs suggest for
 * Ollama; the server ignores it. The connection check sends it too, so it tests what chat will send.
 */
export const NO_API_KEY = "none";

export interface ModelsJson {
  /** The whole document; unknown keys are preserved on write. */
  root: JsonObject;
  providers: JsonObject;
}

/** Reads `models.json`; a missing file is `{}`. Never returns a document it could not parse. */
export async function readModelsJson(configDir: string): Promise<ModelsJson> {
  let text: string;
  try {
    text = await readFile(join(configDir, "models.json"), "utf8");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return { root: {}, providers: {} };
    throw error;
  }
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    throw new HostError("models_invalid", MODELS_INVALID, { key: "models_invalid" });
  }
  if (!isObject(root)) throw new HostError("models_invalid", MODELS_INVALID, { key: "models_invalid" });
  const providers = root.providers ?? {};
  if (!isObject(providers)) throw new HostError("models_invalid", MODELS_INVALID, { key: "models_invalid" });
  return { root, providers };
}

/** Replaces `models.json` atomically so Pi never reads a half-written file. */
export async function writeModelsJson(configDir: string, models: ModelsJson): Promise<void> {
  await mkdir(configDir, { recursive: true });
  const path = join(configDir, "models.json");
  await writeFile(`${path}.tmp`, `${JSON.stringify({ ...models.root, providers: models.providers }, null, 2)}\n`);
  await rename(`${path}.tmp`, path);
}
