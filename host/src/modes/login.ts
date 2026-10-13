import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { HostError, loadPi } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { BUILTIN_PROVIDERS, createRuntime } from "../runtime.js";
import { keepProviderInScope, scopeResolver, scopeUniverse } from "../scope.js";
import { openSettingsIfValid, saveEnabledModels } from "../settings.js";

type AuthInteraction = Parameters<ModelRuntime["login"]>[2];
type AuthPrompt = Parameters<AuthInteraction["prompt"]>[0];

interface PendingPrompt {
  resolve: (value: string) => void;
  reject: (error: Error) => void;
}

/** Bridges Pi's login interaction over the socket: events out, prompts answered by the app. */
function bridge(ctx: ModeContext): AuthInteraction {
  const pending = new Map<number, PendingPrompt>();
  let nextId = 1;
  ctx.conn.listen((message) => {
    const prompt = typeof message.id === "number" ? pending.get(message.id) : undefined;
    if (prompt === undefined || typeof message.id !== "number") return;
    if (message.type === "pipkin_auth_response" && typeof message.value === "string") {
      pending.delete(message.id);
      prompt.resolve(message.value);
    } else if (message.type === "pipkin_auth_cancel") {
      pending.delete(message.id);
      prompt.reject(new Error("cancelled"));
    }
  });
  ctx.signal.addEventListener(
    "abort",
    () => {
      for (const prompt of pending.values()) prompt.reject(new Error("cancelled"));
      pending.clear();
    },
    { once: true },
  );

  return {
    signal: ctx.signal,
    notify(event) {
      ctx.conn.send({ type: "pipkin_auth_event", event });
    },
    prompt(prompt: AuthPrompt) {
      const id = nextId++;
      const { promise, resolve, reject } = Promise.withResolvers<string>();
      const description: JsonObject = { type: prompt.type, message: prompt.message };
      if ("placeholder" in prompt && prompt.placeholder !== undefined) description.placeholder = prompt.placeholder;
      if (prompt.type === "select") description.options = prompt.options;
      if (ctx.signal.aborted) {
        reject(new Error("cancelled"));
        return promise;
      }
      const dismiss = (): void => {
        if (!pending.delete(id)) return;
        ctx.conn.send({ type: "pipkin_auth_prompt_dismiss", id });
        reject(new Error("dismissed"));
      };
      pending.set(id, {
        resolve: (value) => {
          prompt.signal?.removeEventListener("abort", dismiss);
          resolve(value);
        },
        reject: (error) => {
          prompt.signal?.removeEventListener("abort", dismiss);
          reject(error);
        },
      });
      ctx.conn.send({ type: "pipkin_auth_prompt", id, prompt: description });
      if (prompt.signal?.aborted === true) dismiss();
      else prompt.signal?.addEventListener("abort", dismiss, { once: true });
      return promise;
    },
  };
}

export async function login(ctx: ModeContext): Promise<JsonObject> {
  const providerId = stringParam(ctx.params, "providerId");
  if (!(BUILTIN_PROVIDERS as readonly string[]).includes(providerId)) {
    throw new HostError("bad_params", `Pipkin cannot sign in to ${providerId}`);
  }
  const pi = await loadPi(ctx.env.root);
  const runtime = await createRuntime(ctx);
  // ChatGPT sign-in needs a stable device ID, kept in Pipkin's own Pi settings.
  const settings = pi.SettingsManager.create(ctx.env.home, ctx.env.configDir);
  try {
    await runtime.login(providerId, "oauth", bridge(ctx), { getDeviceId: () => settings.getOrCreateDeviceId() });
  } finally {
    await settings.flush();
  }
  // A scope written before sign-in may match none of this subscription's models; they must show now.
  const scope = openSettingsIfValid(ctx, pi);
  if (scope !== undefined && scope.enabledModels.length > 0) {
    const resolve = scopeResolver(pi, await scopeUniverse(runtime, ctx.signal));
    const patterns = await keepProviderInScope(scope.enabledModels, providerId, scope.defaultRef, resolve);
    if (patterns !== undefined) await saveEnabledModels(scope, patterns);
  }
  return {};
}
