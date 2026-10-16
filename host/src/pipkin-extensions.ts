import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { HostError } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";

/** A Pi extension Pipkin makes itself, as the source Pi loads (`host/build.ts` bundles each). */
export interface PipkinExtension {
  name: string;
  source: string;
}

/** The `pipkinExtensions` parameter: Pipkin's own extensions the user left on, each with a plain folder name. */
export function pipkinExtensionsParam(params: JsonObject): PipkinExtension[] {
  const value = params.pipkinExtensions;
  const valid =
    Array.isArray(value) &&
    value.every(
      (item) =>
        isObject(item) &&
        typeof item.name === "string" &&
        /^[a-z0-9][a-z0-9-]*$/.test(item.name) &&
        typeof item.source === "string",
    );
  if (!valid) {
    throw new HostError("bad_params", 'Parameter "pipkinExtensions" must be an array of { name, source } with plain names');
  }
  return value as PipkinExtension[];
}

/**
 * Writes each extension to `<root>/extensions/<name>/index.js` and returns their paths, for Pi's `--extension`. A
 * file is rewritten only when its source changed, and through a rename, so another chat's Pi starting at the same time
 * never loads half a file.
 */
export async function pipkinExtensionFiles(root: string, extensions: PipkinExtension[]): Promise<string[]> {
  const paths: string[] = [];
  for (const { name, source } of extensions) {
    const dir = join(root, "extensions", name);
    const path = join(dir, "index.js");
    if ((await readFile(path, "utf8").catch(() => null)) !== source) {
      await mkdir(dir, { recursive: true });
      const temporary = `${path}.${process.pid}.tmp`;
      await writeFile(temporary, source);
      await rename(temporary, path);
    }
    paths.push(path);
  }
  return paths;
}
