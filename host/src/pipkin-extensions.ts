import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { HostError } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";

/** Where a Pi extension Pipkin ships lives, as `ensure` installs it and `rpc` finds it. */
interface ExtensionLocation {
  name: string;
  /** Whether the extension imports packages that `ensure` installs in Pipkin's Pi runtime. */
  usesRuntimePackages: boolean;
}

/** An extension's source, which `ensure` installs (`host/build.ts` bundles each). */
export interface PipkinExtension extends ExtensionLocation {
  source: string;
}

/** An extension a chat loads, by the SHA-256 (lowercase hex) of the source Pipkin ships. */
export interface PipkinExtensionFingerprint extends ExtensionLocation {
  sha256: string;
}

function isLocation(item: unknown): item is ExtensionLocation & JsonObject {
  return (
    isObject(item) &&
    typeof item.name === "string" &&
    /^[a-z0-9][a-z0-9-]*$/.test(item.name) &&
    typeof item.usesRuntimePackages === "boolean"
  );
}

/** The `ensure` parameter `pipkinExtensions`: every extension Pipkin ships, enabled or not, with a plain folder name. */
export function pipkinExtensionsParam(params: JsonObject): PipkinExtension[] {
  const value = params.pipkinExtensions;
  if (!Array.isArray(value) || !value.every((item): item is PipkinExtension => isLocation(item) && typeof item.source === "string")) {
    throw new HostError(
      "bad_params",
      'Parameter "pipkinExtensions" must be an array of { name, source, usesRuntimePackages } with plain names',
    );
  }
  return value;
}

/** The `rpc` parameter `pipkinExtensions`: the extensions the user left on, each with a plain folder name. */
export function pipkinExtensionFingerprintsParam(params: JsonObject): PipkinExtensionFingerprint[] {
  const value = params.pipkinExtensions;
  const valid = Array.isArray(value) && value.every((item): item is PipkinExtensionFingerprint => isLocation(item) && typeof item.sha256 === "string");
  if (!valid) {
    throw new HostError(
      "bad_params",
      'Parameter "pipkinExtensions" must be an array of { name, sha256, usesRuntimePackages } with plain names',
    );
  }
  return value;
}

/**
 * An extension's `index.js`. One that uses runtime packages goes inside the runtime, where Node finds them and imports
 * the file natively. Any other stays outside, where Pi's loader transpiles it and hands its `@earendil-works/*` and
 * `typebox` imports Pi's own loaded copies instead of loading second ones.
 */
function extensionPath(root: string, { name, usesRuntimePackages }: ExtensionLocation): string {
  return usesRuntimePackages ? join(root, "pi", "extensions", name, "index.js") : join(root, "extensions", name, "index.js");
}

/**
 * Writes each extension to its `index.js`. A file is rewritten only when its source changed, and through a rename, so
 * a chat's Pi starting at the same time never loads half a file.
 */
export async function installPipkinExtensions(root: string, extensions: PipkinExtension[]): Promise<void> {
  for (const extension of extensions) {
    const path = extensionPath(root, extension);
    if ((await readFile(path, "utf8").catch(() => null)) === extension.source) continue;
    await mkdir(dirname(path), { recursive: true });
    const temporary = `${path}.${process.pid}.tmp`;
    await writeFile(temporary, extension.source);
    await rename(temporary, path);
  }
}

/**
 * The installed `index.js` of each extension, for Pi's `--extension`, or null when one is missing or differs from the
 * source Pipkin ships, which `ensure` then installs.
 */
export async function installedPipkinExtensionFiles(
  root: string,
  fingerprints: PipkinExtensionFingerprint[],
): Promise<{ name: string; path: string }[] | null> {
  const files: { name: string; path: string }[] = [];
  for (const fingerprint of fingerprints) {
    const path = extensionPath(root, fingerprint);
    const installed = await readFile(path).catch(() => null);
    if (installed === null || createHash("sha256").update(installed).digest("hex") !== fingerprint.sha256) return null;
    files.push({ name: fingerprint.name, path });
  }
  return files;
}
