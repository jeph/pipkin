import { isObject, type JsonObject } from "../framing.js";
import { failure, type ModeContext } from "../mode.js";
import { authStatus } from "./auth-status.js";
import { deleteBackup, listBackups } from "./backups.js";
import { defaultModel } from "./default-model.js";
import { deleteSessions } from "./delete-sessions.js";
import { localModelsSync } from "./local-models-sync.js";
import { logout } from "./logout.js";
import { modelDefaults } from "./model-defaults.js";
import { providerModels } from "./provider-models.js";
import { providerRemove } from "./provider-remove.js";
import { providerSave } from "./provider-save.js";
import { saveAttachments } from "./save-attachments.js";
import { sessionMessages } from "./session-messages.js";
import { sessions } from "./sessions.js";
import { subagentSessions } from "./subagent-sessions.js";
import { subscriptionModels } from "./subscription-models.js";
import { subscriptionSave } from "./subscription-save.js";

/**
 * The app's one-off requests: each answers once, sends nothing before its answer, and reads its own files afresh, so
 * one long-lived host can answer them all. Requests that stream progress or prompts keep a host of their own.
 */
const OPERATIONS: Record<string, (ctx: ModeContext) => Promise<JsonObject>> = {
  sessions,
  session_messages: sessionMessages,
  subagent_sessions: subagentSessions,
  delete_sessions: deleteSessions,
  save_attachments: saveAttachments,
  auth_status: authStatus,
  logout,
  provider_models: providerModels,
  model_defaults: modelDefaults,
  subscription_models: subscriptionModels,
  provider_save: providerSave,
  provider_remove: providerRemove,
  subscription_save: subscriptionSave,
  local_models_sync: localModelsSync,
  default_model: defaultModel,
  backups: listBackups,
  backup_delete: deleteBackup,
};

async function answer(ctx: ModeContext, op: unknown, params: unknown): Promise<JsonObject> {
  const run = typeof op === "string" && Object.hasOwn(OPERATIONS, op) ? OPERATIONS[op] : undefined;
  if (run === undefined) return { type: "pipkin_result", ok: false, error: `Unknown operation ${String(op)}`, code: "unknown_op" };
  if (!isObject(params)) return { type: "pipkin_result", ok: false, error: "Expected params", code: "bad_request" };
  try {
    return { type: "pipkin_result", ok: true, data: await run({ ...ctx, params }) };
  } catch (error) {
    return failure(error);
  }
}

/**
 * Long-lived host for the app's one-off requests ([OPERATIONS]): sends `pipkin_utility_ready`, then answers each
 * `pipkin_utility_request {id, op, params}` concurrently with the `pipkin_result` that operation's own host would have
 * sent, carrying the request's `id`, until the app hangs up. Pi's SDK is imported once, by the first request that
 * needs it, so the app restarts this host after `ensure` installs another Pi.
 */
export async function utility(ctx: ModeContext): Promise<JsonObject> {
  ctx.conn.send({ type: "pipkin_utility_ready" });
  // Returning exits the process, so a hang-up first lets a save in progress finish its write.
  const inFlight = new Set<Promise<void>>();
  ctx.conn.listen((message) => {
    const { id } = message;
    if (message.type !== "pipkin_utility_request" || typeof id !== "number") return;
    const sent = answer(ctx, message.op, message.params).then((result) => ctx.conn.send({ ...result, id }));
    inFlight.add(sent);
    void sent.finally(() => inFlight.delete(sent));
  });
  if (!ctx.signal.aborted) {
    const { promise, resolve: hungUp } = Promise.withResolvers<void>();
    ctx.signal.addEventListener("abort", () => hungUp(), { once: true });
    await promise;
  }
  await Promise.allSettled(inFlight);
  return {};
}
