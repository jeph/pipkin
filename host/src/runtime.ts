import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { loadPi } from "./env.js";
import type { ModeContext } from "./mode.js";

/** Pi's model/auth runtime over `PI_CODING_AGENT_DIR`, without a model catalog refresh. */
export async function createRuntime(ctx: ModeContext): Promise<ModelRuntime> {
  const pi = await loadPi(ctx.env.root);
  return await pi.ModelRuntime.create({ signal: ctx.signal, refreshOnCreate: false });
}
