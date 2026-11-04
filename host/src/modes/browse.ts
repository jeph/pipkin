import { opendir, readdir, realpath, stat } from "node:fs/promises";
import { dirname, isAbsolute, join, posix, resolve } from "node:path";
import { type HostEnv, grantedSharedStorage, HostError } from "../env.js";
import { IGNORED_FOLDER_NAMES, MAX_DEPTH, SEARCH_LIMIT, type SearchRoot, searchFolders } from "../folder-search.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { errorJson } from "../remote/errors.js";
import { remoteHome } from "../remote/fs.js";
import { findHost, recordHome } from "../remote/hosts.js";
import { quote } from "../remote/script.js";
import { READ_IDLE_MS, type Remote, runScript, runWatched } from "../remote/ssh.js";
import { asRemoteError, connectionCode, envRemotePaths } from "./remote-folder.js";

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

/** [names] in the order the browser lists them: dot folders last, numbers by value. */
function sortFolders(names: string[]): string[] {
  const collator = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });
  return names.toSorted((a, b) => Number(a.startsWith(".")) - Number(b.startsWith(".")) || collator.compare(a, b));
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
  return sortFolders(names);
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
    return { path, parent, dirs: [], ...listingError(code) };
  }
}

/** A listing's `error` and `errorI18n` for Node's error [code]; empty when unknown. */
function listingError(code: string): JsonObject {
  const known = MESSAGES[code];
  if (known !== undefined) return { error: known.error, errorI18n: { key: known.key } };
  if (code === "") return { error: "Can't list this folder (unknown error).", errorI18n: { key: "browse_unlistable_unknown" } };
  return { error: `Can't list this folder (${code}).`, errorI18n: { key: "browse_unlistable", args: { code } } };
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

// A remote host's folders, each request one `ssh` round trip.

/** How long a remote search may run; it answers with what it found by then. */
const REMOTE_SEARCH_TIMEOUT_MS = 20_000;
/** Matches a remote search ranks at most; past them it stops, so a common query can't stream a whole tree back. */
const REMOTE_SEARCH_MATCHES = 500;

/**
 * Remote folder [raw] for the browser, as [listing] gives a local one: `~` is the remote [home], links are resolved
 * with home keeping its spelling, and the parent is offered only when it can be listed.
 */
async function remoteListing(remote: Remote, raw: string, home: string): Promise<JsonObject> {
  const resolved = resolveBrowsePath(raw, home);
  if (resolved === undefined) {
    return { path: raw, parent: null, dirs: [], error: "Paths start with / or ~.", errorI18n: { key: "browse_path_not_absolute" } };
  }
  let fields: string[];
  try {
    const script = [
      `h=${quote(home)}`,
      `p=${quote(resolved)}`,
      // Real paths are captured with an `x` after them, as `$(…)` drops trailing newlines a folder name may end with.
      `H=$(cd -P -- "$h" 2>/dev/null && pwd -P && echo x); H=\${H%x}; H=\${H%$'\\n'}`,
      `if c=$(cd -P -- "$p" 2>/dev/null && pwd -P && echo x); then`,
      `  c=\${c%x}; c=\${c%$'\\n'}`,
      `  if [ "$c" = "$H" ]; then c=$h; elif [ -n "$H" ] && [ "$H" != / ]; then case $c in "$H"/*) c=$h\${c#"$H"} ;; esac; fi`,
      "else c=$p; fi",
      `printf '%s\\0' "$c"`,
      `d=\${c%/*}; d=\${d:-/}`,
      `if [ "$c" != / ] && [ -r "$d" ] && [ -x "$d" ]; then printf '%s\\0' "$d"; else printf '\\0'; fi`,
      `if [ ! -e "$c" ]; then if [ -d "$d" ] && [ ! -x "$d" ]; then printf 'EACCES\\0'; else printf 'ENOENT\\0'; fi; exit 0; fi`,
      `[ -d "$c" ] || { printf 'ENOTDIR\\0'; exit 0; }`,
      `[ -r "$c" ] && cd -- "$c" 2>/dev/null || { printf 'EACCES\\0'; exit 0; }`,
      `printf 'ok\\0'`,
      "shopt -s dotglob nullglob",
      `for f in *; do [ -d "$f" ] && printf '%s\\0' "$f"; done`,
      "exit 0",
    ].join("\n");
    const result = await runScript(remote, script, { idleTimeoutMs: READ_IDLE_MS });
    fields = result.stdout.toString("utf8").split("\0").slice(0, -1);
    if (result.code !== 0 || fields.length < 3) throw new Error(result.stderr.trim() || `exit ${String(result.code)}`);
  } catch (error) {
    const failure = asRemoteError(remote, error);
    return { path: resolved, parent: null, dirs: [], error: failure.message, errorI18n: failure.i18n, ...connectionCode(failure) };
  }
  const [path = resolved, parent = "", status = "", ...dirs] = fields;
  const found = { path, parent: parent === "" ? null : parent };
  if (status === "ok") return { ...found, dirs: sortFolders(dirs) };
  if (status === "EACCES") {
    const host = remote.host.name;
    return { ...found, dirs: [], error: `${host} doesn't let you open this folder.`, errorI18n: { key: "remote_browse_no_access", args: { host } } };
  }
  return { ...found, dirs: [], ...listingError(status) };
}

/** [value] as a `find -iname`/`-ipath` pattern that matches it literally. */
function literalPattern(value: string): string {
  return value.replaceAll(/[\\*?[]/g, (char) => `\\${char}`);
}

/** Where a remote search for [query] looks, and for what: a typed path's folder, else all of [home]. */
function remoteSearchPlan(query: string, home: string): { root: string; term: string } | undefined {
  const typed = query.trim();
  if (typed === "") return undefined;
  if (typed === "~" || typed === ".") return { root: home, term: "" };
  let anchored: string | undefined;
  if (typed.startsWith("~/") || typed.startsWith("./")) anchored = `${home}${typed.slice(1)}`;
  else if (typed.startsWith("/")) anchored = typed;
  if (anchored === undefined) return { root: home, term: typed };
  const slash = anchored.lastIndexOf("/");
  return { root: posix.resolve(anchored.slice(0, slash) || "/"), term: anchored.slice(slash + 1) };
}

/** How well folder [path] under [root] matches [term]: its name exactly, then by prefix, then anywhere. */
function remoteRank(path: string, root: string, term: string): { tier: number; depth: number } {
  const name = posix.basename(path).toLowerCase();
  const lower = term.toLowerCase();
  const tier = name === lower ? 0 : name.startsWith(lower) ? 1 : name.includes(lower) ? 2 : 3;
  return { tier, depth: posix.relative(root, path).split("/").length };
}

/**
 * [query]'s matches on the remote, best first: folders under its home whose name holds the query literally, or, for a
 * typed path (`~/src/ap`, `/etc/ss`), under that path's folder. Walks [MAX_DEPTH] deep without entering hidden or
 * [IGNORED_FOLDER_NAMES] folders, as the local search does, and runs under the watchdog, so [signal] (a newer search)
 * and the time limit stop `find` on the remote too. Undefined once [signal] aborts; none when the host can't be reached.
 */
async function remoteSearch(remote: Remote, query: string, home: string, signal: AbortSignal): Promise<string[] | undefined> {
  const plan = remoteSearchPlan(query, home);
  if (plan === undefined) return [];
  const { root, term } = plan;
  // A NUL can't reach the remote; such a query matches nothing.
  if (query.includes("\0")) return [];
  const test = term.includes("/") ? "-ipath" : "-iname";
  const prune = ["-name '.*'", ...[...IGNORED_FOLDER_NAMES].map((name) => `-name ${quote(name)}`)].join(" -o ");
  const depth = term === "" ? 1 : MAX_DEPTH;
  const command = `find ${quote(root)} -mindepth 1 -maxdepth ${String(depth)} \\( ${prune} \\) -prune -o -type d ${test} ${quote(`*${literalPattern(term)}*`)} -print0 2>/dev/null`;
  const enough = new AbortController();
  const chunks: Buffer[] = [];
  let matches = 0;
  const onStdout = (chunk: Buffer): void => {
    chunks.push(chunk);
    for (const byte of chunk) if (byte === 0) matches++;
    if (matches >= REMOTE_SEARCH_MATCHES) enough.abort();
  };
  try {
    await runWatched(remote, command, "/", { signal: AbortSignal.any([signal, enough.signal]), timeoutMs: REMOTE_SEARCH_TIMEOUT_MS, onStdout });
  } catch (error) {
    if (signal.aborted) return undefined;
    // Cut short by its limits, a search answers with what it found; a host that can't be reached has nothing.
    if (error instanceof HostError) return [];
  }
  if (signal.aborted) return undefined;
  const paths = Buffer.concat(chunks).toString("utf8").split("\0").slice(0, -1);
  const ranked = paths.map((path) => ({ path, ...remoteRank(path, root, term) }));
  ranked.sort((a, b) => a.tier - b.tier || a.depth - b.depth || a.path.localeCompare(b.path));
  return [...new Set(ranked.map((entry) => entry.path))].slice(0, SEARCH_LIMIT);
}

type RemotePlace = { kind: "remote_home" | "remote_root"; path: string };

/** A host the browser reached: where `~` is there, and the places it offers. */
interface OpenedHost {
  remote: Remote;
  home: string;
  places: RemotePlace[];
}

/**
 * Host [hostId]'s places, its home and `/`, and the home `~` stands for. Reaching the host checks the connection, so
 * the browser opens on a host it can list or says why it can't.
 */
async function remotePlaces(env: HostEnv, hostId: string): Promise<OpenedHost> {
  const paths = envRemotePaths(env);
  const remote = { paths, host: await findHost(paths, hostId) };
  let probed: string;
  try {
    probed = await remoteHome(remote, { idleTimeoutMs: READ_IDLE_MS });
  } catch (error) {
    throw asRemoteError(remote, error);
  }
  const home = remote.host.home ?? probed;
  // A key host reached here first, without a connect, still gets its home saved, so its folders read `~/…`.
  if (remote.host.home === null) await recordHome(paths, hostId, probed);
  return {
    remote,
    home,
    places: [
      { kind: "remote_home", path: home },
      { kind: "remote_root", path: "/" },
    ],
  };
}

/** The remote side of [browse], for host [hostId]. */
async function browseRemote(ctx: ModeContext, hostId: string): Promise<void> {
  let opened: OpenedHost | undefined;
  let failure: HostError | undefined;
  try {
    opened = await remotePlaces(ctx.env, hostId);
    ctx.conn.send({ type: "pipkin_places", places: opened.places });
  } catch (error) {
    failure = error instanceof HostError ? error : new HostError("remote_failed", error instanceof Error ? error.message : String(error));
    ctx.conn.send({ type: "pipkin_places", places: [], error: errorJson(failure) });
  }
  /** A listing on a host that couldn't be reached says why, as the places did. */
  const unreached = (path: string): JsonObject => ({
    path,
    parent: null,
    dirs: [],
    ...(failure === undefined ? {} : { error: failure.message, errorI18n: failure.i18n, ...connectionCode(failure) }),
  });
  let running: AbortController | undefined;
  ctx.conn.listen((message) => {
    const { id } = message;
    if (typeof id !== "number") return;
    if (message.type === "pipkin_list" && typeof message.path === "string") {
      const reply = opened === undefined ? Promise.resolve(unreached(message.path)) : remoteListing(opened.remote, message.path, opened.home);
      void reply.then((result) => {
        ctx.conn.send({ type: "pipkin_listing", id, ...result });
      });
    } else if (message.type === "pipkin_search" && typeof message.query === "string") {
      running?.abort();
      const current = new AbortController();
      running = current;
      const searched = opened === undefined ? Promise.resolve([]) : remoteSearch(opened.remote, message.query, opened.home, AbortSignal.any([current.signal, ctx.signal]));
      void searched.then((paths) => {
        if (paths !== undefined && !current.signal.aborted) ctx.conn.send({ type: "pipkin_search_results", id, paths });
      });
    }
  });
}

/**
 * Long-lived folder listing for the app's folder browser: sends `pipkin_places` once, then answers
 * each `pipkin_list` with a `pipkin_listing` and each `pipkin_search` with `pipkin_search_results`
 * until the app hangs up. Listing failures are part of the listing, so the browser can show them in
 * place. A search still running when the next arrives stops without answering.
 *
 * With a `hostId`, it browses that remote host instead: its places are `remote_home` and `remote_root`, and a host that
 * can't be reached sends `pipkin_places` with no places and an `error` (`{code, message, i18n}`). A listing that fails
 * for the connection adds its code as `errorCode`.
 */
export async function browse(ctx: ModeContext): Promise<JsonObject> {
  const hostId = ctx.params.hostId;
  if (typeof hostId === "string") await browseRemote(ctx, hostId);
  else await browseLocal(ctx);
  if (!ctx.signal.aborted) {
    await new Promise<void>((done) => {
      ctx.signal.addEventListener("abort", () => done(), { once: true });
    });
  }
  return {};
}

/** The local side of [browse]: Termux's home and storage. */
async function browseLocal(ctx: ModeContext): Promise<void> {
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
}
