import { isDeepStrictEqual } from "node:util";
import { loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { createRuntime } from "../runtime.js";
import { scopeResolver, scopeUniverse, withoutMisdirected } from "../scope.js";
import { openSettingsIfValid, saveEnabledModels } from "../settings.js";

export async function logout(ctx: ModeContext): Promise<JsonObject> {
  const providerId = stringParam(ctx.params, "providerId");
  const runtime = await createRuntime(ctx);
  await runtime.logout(providerId, { signal: ctx.signal });
  // Without its key, the provider's patterns may name OpenRouter's copies of its models instead.
  const pi = await loadPi(ctx.env.root);
  const scope = openSettingsIfValid(ctx, pi);
  if (scope === undefined || scope.enabledModels.length === 0) return {};
  const universe = await scopeUniverse(await createRuntime(ctx), ctx.signal);
  const patterns = await withoutMisdirected(scope.enabledModels, universe, scopeResolver(pi, universe));
  if (!isDeepStrictEqual(patterns, scope.enabledModels)) await saveEnabledModels(scope, patterns);
  return {};
}
