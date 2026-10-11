import type { JsonObject } from "./framing.js";
import type { ModeContext } from "./mode.js";
import { type ModelDef, parseModelDef } from "./model-def.js";
import { createRuntime } from "./runtime.js";

/** Providers whose copy of a shared model id is preferred, best first. */
const PREFERRED_PROVIDERS = ["openai", "openai-codex", "anthropic", "google"];

export type Catalogue = (id: string) => ModelDef | undefined;

/**
 * Looks model ids up in Pi's built-in catalogue (never `models.json`). When several built-in
 * providers list an id, the copy with the requested `api` wins, then `PREFERRED_PROVIDERS` order,
 * then catalogue order.
 */
export async function loadCatalogue(ctx: ModeContext, api: string): Promise<Catalogue> {
  const runtime = await createRuntime(ctx, { builtinOnly: true });
  const best = new Map<string, { rank: number; def: ModelDef }>();
  for (const model of runtime.getModels()) {
    const preferred = PREFERRED_PROVIDERS.indexOf(model.provider);
    const rank = (model.api === api ? 0 : 100) + (preferred === -1 ? PREFERRED_PROVIDERS.length : preferred);
    const current = best.get(model.id);
    if (current === undefined || rank < current.rank) {
      best.set(model.id, { rank, def: parseModelDef(model.id, model as unknown as JsonObject) });
    }
  }
  return (id) => best.get(id)?.def;
}
