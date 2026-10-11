import type { SettingsManager } from "@earendil-works/pi-coding-agent";
import { HostError, type PiSdk } from "./env.js";
import type { ModeContext } from "./mode.js";

export const SETTINGS_INVALID = "settings.json is not valid. Ask the agent to fix it.";

/** Pi's global settings, read and written through Pi's own lock-and-merge `SettingsManager`. */
export interface PiSettings {
  manager: SettingsManager;
  /** `enabledModels` patterns; empty means every model is in scope. */
  enabledModels: string[];
  /** `defaultProvider/defaultModel`, when both are set. */
  defaultRef: string | undefined;
}

/** Throws `settings_invalid` when settings.json cannot be parsed or `enabledModels` is not a list of strings. */
export function openSettings(ctx: ModeContext, pi: PiSdk): PiSettings {
  const manager = pi.SettingsManager.create(ctx.env.home, ctx.env.configDir);
  if (manager.drainErrors().some((error) => error.scope === "global")) throw new HostError("settings_invalid", SETTINGS_INVALID);
  const enabledModels: unknown = manager.getEnabledModels() ?? [];
  if (!Array.isArray(enabledModels) || !enabledModels.every((pattern) => typeof pattern === "string")) {
    throw new HostError("settings_invalid", SETTINGS_INVALID);
  }
  const provider = manager.getDefaultProvider();
  const model = manager.getDefaultModel();
  return { manager, enabledModels, defaultRef: provider && model ? `${provider}/${model}` : undefined };
}

/**
 * The settings, or undefined when settings.json is invalid: Pi then ignores it, so there is no scope
 * for a provider change to maintain.
 */
export function openSettingsIfValid(ctx: ModeContext, pi: PiSdk): PiSettings | undefined {
  try {
    return openSettings(ctx, pi);
  } catch (error) {
    if (error instanceof HostError && error.code === "settings_invalid") return undefined;
    throw error;
  }
}

/** Writes `enabledModels` (removing it when empty) and waits for Pi's locked write. */
export async function saveEnabledModels(settings: PiSettings, patterns: readonly string[]): Promise<void> {
  settings.manager.setEnabledModels(patterns.length > 0 ? [...patterns] : undefined);
  await settings.manager.flush();
  const [failure] = settings.manager.drainErrors();
  if (failure !== undefined) throw failure.error;
}
