import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { HostError } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";

/** A Pi extension Pipkin ships, as the source Pi loads (`host/build.ts` bundles each). */
export interface PipkinExtension {
  name: string;
  source: string;
  /** Whether the extension imports packages that `ensure` installs in Pipkin's Pi runtime. */
  usesRuntimePackages: boolean;
}

/** The `pipkinExtensions` parameter: the extensions Pipkin ships that the user left on, each with a plain folder name. */
export function pipkinExtensionsParam(params: JsonObject): PipkinExtension[] {
  const value = params.pipkinExtensions;
  const valid =
    Array.isArray(value) &&
    value.every(
      (item) =>
        isObject(item) &&
        typeof item.name === "string" &&
        /^[a-z0-9][a-z0-9-]*$/.test(item.name) &&
        typeof item.source === "string" &&
        typeof item.usesRuntimePackages === "boolean",
    );
  if (!valid) {
    throw new HostError(
      "bad_params",
      'Parameter "pipkinExtensions" must be an array of { name, source, usesRuntimePackages } with plain names',
    );
  }
  return value as PipkinExtension[];
}

/**
 * Writes each extension to its `index.js` and returns their paths, for Pi's `--extension`. An extension that uses
 * runtime packages goes inside the runtime, where Node finds them and imports the file natively. Any other stays
 * outside, where Pi's loader transpiles it and hands its `@earendil-works/*` and `typebox` imports Pi's own loaded
 * copies instead of loading second ones. A file is rewritten only when its source changed, and through a rename, so
 * another chat's Pi starting at the same time never loads half a file.
 */
export async function pipkinExtensionFiles(root: string, extensions: PipkinExtension[]): Promise<string[]> {
  const paths: string[] = [];
  for (const { name, source, usesRuntimePackages } of extensions) {
    const dir = usesRuntimePackages ? join(root, "pi", "extensions", name) : join(root, "extensions", name);
    const path = join(dir, "index.js");
    paths.push(path);
    if ((await readFile(path, "utf8").catch(() => null)) === source) continue;
    await mkdir(dir, { recursive: true });
    const temporary = `${path}.${process.pid}.tmp`;
    await writeFile(temporary, source);
    await rename(temporary, path);
  }
  return paths;
}
