import { opendir, readdir, realpath, stat } from "node:fs/promises";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { type HostEnv, grantedSharedStorage } from "../env.js";
import { SEARCH_LIMIT, type SearchRoot, searchFolders } from "../folder-search.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";

/** Where Android mounts shared storage volumes; `emulated` and `self` there are not volumes themselves. */
const STORAGE = "/storage";

/** What the folder browser shows for Node's error codes, with the key of its string resource. */
const MESSAGES: Record<string, { error: string; key: string }> = {
  EACCES: { error: "Termux can't open this folder.", key: "browse_no_access" },
  EPERM: { error: "Termux can't open this folder.", key: "browse_no_access" },
  ENOENT: { error: "This folder doesn't exist.", key: "browse_folder_missing" },
  ENOTDIR: { error: "This isn't a folder.", key: "not_a_folder" },
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
  if (resolved === undefined) {
    return { path: raw, parent: null, dirs: [], error: "Paths start with / or ~.", errorI18n: { key: "browse_path_not_absolute" } };
  }
  const path = await canonical(resolved, home);
  const parent = path === "/" ? null : await openableParent(dirname(path));
  try {
    return { path, parent, dirs: await subfolders(path) };
  } catch (error) {
    const code = error instanceof Error && "code" in error && typeof error.code === "string" ? error.code : "";
    const known = MESSAGES[code];
    if (known !== undefined) return { path, parent, dirs: [], error: known.error, errorI18n: { key: known.key } };
    if (code === "") {
      return { path, parent, dirs: [], error: "Can't list this folder (unknown error).", errorI18n: { key: "browse_unlistable_unknown" } };
    }
    return { path, parent, dirs: [], error: `Can't list this folder (${code}).`, errorI18n: { key: "browse_unlistable", args: { code } } };
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

type Place = { kind: "home" | "shared" | "volume"; path: string; name?: string };

/** Termux home, phone storage when Termux may read it, and any other mounted volume. */
async function places(env: HostEnv): Promise<Place[]> {
  const found: Place[] = [{ kind: "home", path: env.home }];
  const shared = await grantedSharedStorage(env);
  if (shared !== null) found.push({ kind: "shared", path: shared });
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

/** [query]'s matches in every place, spelled like listings so a folder can't become two workspaces. */
async function search(query: string, found: Place[], home: string, cancelled: () => boolean): Promise<string[] | undefined> {
  const roots: SearchRoot[] = found.map((place) => ({ path: place.path, aliases: place.kind === "home" ? ["~"] : [] }));
  const paths = await searchFolders(query, roots, cancelled);
  if (paths === undefined) return undefined;
  const canonicalPaths = await Promise.all(paths.map((path) => canonical(path, home)));
  return [...new Set(canonicalPaths)].slice(0, SEARCH_LIMIT);
}

/**
 * Long-lived folder listing for the app's folder browser: sends `pipkin_places` once, then answers
 * each `pipkin_list` with a `pipkin_listing` and each `pipkin_search` with `pipkin_search_results`
 * until the app hangs up. Listing failures are part of the listing, so the browser can show them in
 * place. A search still running when the next arrives stops without answering.
 */
export async function browse(ctx: ModeContext): Promise<JsonObject> {
  const home = ctx.env.home;
  const found = await places(ctx.env);
  ctx.conn.send({ type: "pipkin_places", places: found });
  let latestSearch: number | undefined;
  ctx.conn.listen((message) => {
    const { id } = message;
    if (typeof id !== "number") return;
    if (message.type === "pipkin_list" && typeof message.path === "string") {
      void listing(message.path, home).then((result) => {
        ctx.conn.send({ type: "pipkin_listing", id, ...result });
      });
    } else if (message.type === "pipkin_search" && typeof message.query === "string") {
      latestSearch = id;
      void search(message.query, found, home, () => latestSearch !== id).then((paths) => {
        if (paths !== undefined && latestSearch === id) ctx.conn.send({ type: "pipkin_search_results", id, paths });
      });
    }
  });
  if (!ctx.signal.aborted) {
    await new Promise<void>((done) => {
      ctx.signal.addEventListener("abort", () => done(), { once: true });
    });
  }
  return {};
}
