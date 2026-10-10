import { randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { HostError } from "../env.js";
import { isObject, type JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";

const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;

/** A plain file name: no directories, no NULs, never `.`/`..` or hidden. */
function safeName(name: string): string {
  const base = basename(name.replaceAll("\\", "/")).replaceAll("\0", "").replace(/^\.+/, "");
  return base === "" ? "file" : base;
}

/** `report.pdf`, `report-2.pdf`, … so two picks with one name both survive. */
function uniqueName(name: string, taken: Set<string>): string {
  if (!taken.has(name)) return name;
  const dot = name.lastIndexOf(".");
  const [stem, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ""];
  for (let n = 2; ; n++) {
    const candidate = `${stem}-${n}${ext}`;
    if (!taken.has(candidate)) return candidate;
  }
}

/**
 * Writes files attached in the app into a fresh folder under `$PIPKIN_ROOT/attachments`, where Pi's
 * tools can read them; Pi's RPC `prompt` itself only carries images.
 */
export async function saveAttachments(ctx: ModeContext): Promise<JsonObject> {
  const files = ctx.params.files;
  if (!Array.isArray(files) || files.length === 0) throw new HostError("bad_params", 'Parameter "files" must be a non-empty array');
  const decoded = files.map((file) => {
    if (!isObject(file) || typeof file.name !== "string" || typeof file.data !== "string" || !BASE64.test(file.data)) {
      throw new HostError("bad_params", "Each file needs a name and base64 data");
    }
    return { name: safeName(file.name), bytes: Buffer.from(file.data, "base64") };
  });

  const stamp = new Date().toISOString().replaceAll(/[-:]/g, "").replace(/\..*$/, "");
  const dir = join(ctx.env.root, "attachments", `${stamp}-${randomBytes(3).toString("hex")}`);
  await mkdir(dir, { recursive: true });
  const taken = new Set<string>();
  const paths: string[] = [];
  for (const file of decoded) {
    const name = uniqueName(file.name, taken);
    taken.add(name);
    const path = join(dir, name);
    await writeFile(path, file.bytes, { flag: "wx" });
    paths.push(path);
  }
  return { paths };
}
