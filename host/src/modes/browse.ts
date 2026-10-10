import { opendir, readdir, realpath, stat } from "node:fs/promises";
import { dirname, isAbsolute, join, resolve } from "node:path";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";

/** Where Android mounts shared storage volumes; `emulated` and `self` there are not volumes themselves. */
const STORAGE = "/storage";

const MESSAGES: Record<string, string> = {
  EACCES: "Termux can't open this folder.",
  EPERM: "Termux can't open this folder.",
  ENOENT: "This folder doesn't exist.",
  ENOTDIR: "This isn't a folder.",
};

/** `~` and `~/…` are Termux home; anything else must be absolute. `..` segments are normalised. */
function resolveBrowsePath(raw: string, home: string): string | undefined {
  if (raw === "~") return home;
  if (raw.startsWith("~/")) return resolve(home, raw.slice(2));
  return isAbsolute(raw) ? resolve(raw) : undefined;
}

async function isDirectory(path: string): Promise<boolean> {
  try {
    return (await stat(path)).isDirectory();
  } catch {
    return false;
  }
}

/** Subfolder names, following symlinks (Termux's `~/storage/*` are links); dot folders last. */
async function subfolders(path: string): Promise<string[]> {
  const entries = await readdir(path, { withFileTypes: true });
  const names: string[] = [];
  for (const entry of entries) {
    if (entry.isDirectory() || (entry.isSymbolicLink() && (await isDirectory(join(path, entry.name))))) {
      names.push(entry.name);
    }
  }
  const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });
  return names.toSorted((a, b) => Number(a.startsWith(".")) - Number(b.startsWith(".")) || collator.compare(a, b));
}

/**
 * [path] with links resolved, so a folder reached through one (Termux's `~/storage/*`) has a single
 * spelling and can't become two workspaces. Termux home keeps the spelling Termux reports, since on
 * Android it lies under the `/data/data` link. Unreadable paths stay as given; their listing says why.
 */
async function canonical(path: string, home: string): Promise<string> {
  try {
    const [real, realHome] = await Promise.all([realpath(path), realpath(home)]);
    if (real === realHome) return home;
    return real.startsWith(`${realHome}/`) ? home + real.slice(realHome.length) : real;
  } catch {
    return path;
  }
}

async function listing(raw: string, home: string): Promise<JsonObject> {
  const resolved = resolveBrowsePath(raw, home);
  if (resolved === undefined) return { path: raw, parent: null, dirs: [], error: "Paths start with / or ~." };
  const path = await canonical(resolved, home);
  const parent = path === "/" ? null : await openableParent(dirname(path));
  try {
    return { path, parent, dirs: await subfolders(path) };
  } catch (error) {
    const code = error instanceof Error && "code" in error && typeof error.code === "string" ? error.code : "";
    return { path, parent, dirs: [], error: MESSAGES[code] ?? `Can't list this folder (${code || "unknown error"}).` };
  }
}

/** [parent] when Termux can list it, so the browser never offers a step up into a refusal. */
async function openableParent(parent: string): Promise<string | null> {
  try {
    await (await opendir(parent)).close();
    return parent;
  } catch {
    return null;
  }
}

/** Termux home, phone storage (through `termux-setup-storage`'s link) and any other mounted volume. */
async function places(home: string): Promise<JsonObject[]> {
  const found: JsonObject[] = [{ kind: "home", path: home }];
  try {
    found.push({ kind: "shared", path: await realpath(join(home, "storage", "shared")) });
  } catch {
    // Storage access not set up yet; home is still browsable.
  }
  try {
    for (const name of await readdir(STORAGE)) {
      const path = join(STORAGE, name);
      if (name !== "emulated" && name !== "self" && (await isDirectory(path))) found.push({ kind: "volume", path, name });
    }
  } catch {
    // `/storage` is unreadable or absent (not Android); the other places still apply.
  }
  return found;
}

/**
 * Long-lived folder listing for the app's folder browser: sends `pipkin_places` once, then answers
 * each `pipkin_list` with a `pipkin_listing` until the app hangs up. Listing failures are part of the
 * listing, so the browser can show them in place.
 */
export async function browse(ctx: ModeContext): Promise<JsonObject> {
  const home = ctx.env.home;
  ctx.conn.send({ type: "pipkin_places", places: await places(home) });
  ctx.conn.listen((message) => {
    if (message.type !== "pipkin_list" || typeof message.id !== "number" || typeof message.path !== "string") return;
    const { id, path } = message;
    void listing(path, home).then((result) => {
      ctx.conn.send({ type: "pipkin_listing", id, ...result });
    });
  });
  if (!ctx.signal.aborted) {
    await new Promise<void>((done) => {
      ctx.signal.addEventListener("abort", () => done(), { once: true });
    });
  }
  return {};
}
