import type * as PiModule from "@earendil-works/pi-coding-agent";
import { readFile } from "node:fs/promises";
import os from "node:os";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";

export const PI_PACKAGE = "@earendil-works/pi-coding-agent";

export type PiSdk = typeof PiModule;

/** Values the Termux bootstrap exports before exec'ing node. */
export interface HostEnv {
  token: string;
  /** `$HOME/.local/share/pipkin`: Pipkin-managed state, including the pinned Pi install. */
  root: string;
  /** `PI_CODING_AGENT_DIR`, `$HOME/.pipkin/pi`: Pi's config, sessions and user-installed packages. */
  configDir: string;
  /** Where Pipkin kept `configDir` on shared storage before it moved into Termux; unset when there is no such place. */
  legacyConfigDir: string | undefined;
  home: string;
  /** Termux's `$PREFIX`, the root of its packages. */
  prefix: string;
}

/** A failure with a machine-readable code, reported as `pipkin_result` `ok:false`. */
export class HostError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function piPackageDir(root: string): string {
  return join(root, "pi", "node_modules", PI_PACKAGE);
}

/** The installed Pi version, or null when Pipkin has not installed Pi yet. */
export async function installedPiVersion(root: string): Promise<string | null> {
  try {
    const manifest = JSON.parse(await readFile(join(piPackageDir(root), "package.json"), "utf8")) as unknown;
    if (manifest !== null && typeof manifest === "object" && "version" in manifest && typeof manifest.version === "string") {
      return manifest.version;
    }
    return null;
  } catch {
    return null;
  }
}

/** The Pi SDK is never bundled: it is loaded from the pinned install so host.mjs stays small. */
export async function loadPi(root: string): Promise<PiSdk> {
  return (await import(pathToFileURL(join(piPackageDir(root), "dist", "index.js")).href)) as PiSdk;
}

export function readHostEnv(): HostEnv | undefined {
  const token = process.env.PIPKIN_TOKEN;
  const root = process.env.PIPKIN_ROOT;
  const configDir = process.env.PI_CODING_AGENT_DIR;
  const prefix = process.env.PREFIX;
  if (!token || !root || !configDir || !prefix) return undefined;
  const legacyConfigDir = process.env.PIPKIN_LEGACY_CONFIG_DIR || undefined;
  return { token, root, configDir, legacyConfigDir, home: process.env.HOME ?? os.homedir(), prefix };
}

/** The host's own variables, which Pi, shells and the commands they run never inherit. */
export function withoutHostVariables(environment: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const {
    PIPKIN_TOKEN: _token,
    PIPKIN_ROOT: _root,
    PI_CODING_AGENT_DIR: _configDir,
    PIPKIN_LEGACY_CONFIG_DIR: _legacyConfigDir,
    ...rest
  } = environment;
  return rest;
}

/** `~/.pipkin/backups`: configuration backups, next to the config folder and on the same filesystem. */
export function backupsDir(env: HostEnv): string {
  return join(dirname(env.configDir), "backups");
}
