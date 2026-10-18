import type * as PiModule from "@earendil-works/pi-coding-agent";
import { constants } from "node:fs";
import { access, readFile, realpath } from "node:fs/promises";
import os from "node:os";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { isObject } from "./framing.js";

export const PI_PACKAGE = "@earendil-works/pi-coding-agent";

export type PiSdk = typeof PiModule;

/** Values the Termux bootstrap exports before exec'ing node. */
export interface HostEnv {
  token: string;
  /** `$HOME/.local/share/pipkin`: Pipkin-managed state, including the pinned Pi install. */
  root: string;
  /** `PI_CODING_AGENT_DIR`, `$HOME/.pipkin/pi`: Pi's config, sessions and user-installed packages. */
  configDir: string;
  home: string;
  /** Termux's `$PREFIX`, the root of its packages. */
  prefix: string;
  /** Android's shared storage, `$EXTERNAL_STORAGE` (`/sdcard`); Termux reaches it only with All files access. */
  sharedStorage: string;
}

/**
 * A text the app shows the user, named by a key the app looks up among its string resources (`HostText.kt`), with the
 * values of its placeholders. The English text always travels beside it: the app falls back to it for a key it doesn't
 * know, and logs it. A placeholder whose value is itself a text the app can show sends that text's English under its
 * name and the text under `<name>I18n`, as fields do.
 */
export interface HostText {
  key: string;
  args?: Record<string, string | number | HostText>;
}

/** A failure with a machine-readable code, reported as `pipkin_result` `ok:false`; [i18n] names [message] for the app. */
export class HostError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly i18n?: HostText,
  ) {
    super(message);
  }
}

/** The failure each mode reports, under its own [code], for a workspace folder at [path] that isn't there. */
export function folderNotFound(code: string, path: string): HostError {
  return new HostError(code, `Folder not found: ${path}`, { key: "folder_not_found", args: { path } });
}

export function piPackageDir(root: string): string {
  return join(root, "pi", "node_modules", PI_PACKAGE);
}

/** The pinned Pi's CLI script, from its manifest's `bin.pi`. */
export async function piEntry(root: string): Promise<string> {
  const dir = piPackageDir(root);
  const manifest = JSON.parse(await readFile(join(dir, "package.json"), "utf8")) as unknown;
  const bin = isObject(manifest) ? manifest.bin : undefined;
  const entry = typeof bin === "string" ? bin : isObject(bin) ? bin.pi : undefined;
  if (typeof entry !== "string") throw new Error("Agent package.json has no bin.pi");
  return join(dir, entry);
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
  return {
    token,
    root,
    configDir,
    home: process.env.HOME ?? os.homedir(),
    prefix,
    sharedStorage: process.env.EXTERNAL_STORAGE || "/sdcard",
  };
}

/**
 * Shared storage's real folder when Termux has storage access, else null. Without access Android still lets an app
 * list the folder's top level, so this asks for write access. Granting access in Android's settings creates no
 * `~/storage` links, so this goes to the folder itself rather than through them.
 */
export async function grantedSharedStorage(env: HostEnv): Promise<string | null> {
  try {
    const path = await realpath(env.sharedStorage);
    await access(path, constants.W_OK);
    return path;
  } catch {
    return null;
  }
}

/** The host's own variables, which Pi, shells and the commands they run never inherit. */
export function withoutHostVariables(environment: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const { PIPKIN_TOKEN: _token, PIPKIN_ROOT: _root, PI_CODING_AGENT_DIR: _configDir, ...rest } = environment;
  return rest;
}

/** `~/.pipkin/backups`: configuration backups, next to the config folder and on the same filesystem. */
export function backupsDir(env: HostEnv): string {
  return join(dirname(env.configDir), "backups");
}
