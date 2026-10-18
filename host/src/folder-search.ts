/*
 * Ported from Paseo's directory search (https://github.com/getpaseo/paseo, commit b26b923): `searchDirectoryEntries` in
 * packages/server/src/utils/directory-suggestions.ts and `scorePathMatch` in
 * packages/protocol/src/search/text-match.ts. Copyright (c) 2025-present Mohamed Boudra,
 * licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * Changed for Pipkin: folders only, as Paseo's add-project picker asks for; several roots (Termux
 * home and each storage volume) instead of one; results keep each root's own spelling; no Git
 * ignore rules, file matches, suffix mode or shared filesystem-call limiter; a search stops early
 * once a newer one starts.
 */
import { readdir, realpath, stat } from "node:fs/promises";
import path from "node:path";

/** A tree to search: [path] as results spell it, and prefixes such as `~` that name it in a query. */
export interface SearchRoot {
  path: string;
  aliases: readonly string[];
}

export const SEARCH_LIMIT = 30;

const MAX_DEPTH = 12;
const MAX_FOLDERS_SCANNED = 20_000;
/** Past this many folders a root stops as soon as one result is a confident match. */
const CONFIDENT_RESULT_SCAN_THRESHOLD = 5_000;
const MAX_CONFIDENT_FUZZY_SKIPS_PER_CHARACTER = 2;
const LIST_CACHE_TTL_MS = 8_000;
const LIST_CACHE_MAX_ENTRIES = 4_000;
const NO_SEGMENT_INDEX = Number.MAX_SAFE_INTEGER;
const NO_MATCH_OFFSET = Number.MAX_SAFE_INTEGER;
const NO_FUZZY_SCORE = Number.MAX_SAFE_INTEGER;
const NO_MATCH_TIER = 5;
const IGNORED_FOLDER_NAMES = new Set([
  "node_modules",
  "venv",
  "env",
  "virtualenv",
  "dist",
  "build",
  "target",
  "out",
  "coverage",
  "vendor",
  "__pycache__",
  ".git",
]);

interface QueryPlan {
  isPathQuery: boolean;
  parentPart: string;
  searchTerm: string;
  normalizedQuery: string;
}

interface Search {
  root: string;
  resolvedRoot: string;
  plan: QueryPlan;
  cancelled: () => boolean;
}

interface Folder {
  name: string;
  resolvedPath: string;
  viaSymlink: boolean;
}

interface Walked extends Folder {
  visiblePath: string;
  depth: number;
}

interface WalkStart {
  visiblePath: string;
  resolvedPath: string;
}

interface RankFields {
  matchTier: number;
  segmentIndex: number;
  matchOffset: number;
  fuzzyScore: number;
}

interface Ranked extends RankFields {
  path: string;
  depth: number;
}

interface ListCacheEntry {
  expiresAt: number;
  modifiedAtMs: number;
  changedAtMs: number;
  entries: { name: string; link: boolean }[];
}

const listCache = new Map<string, ListCacheEntry>();

/**
 * Folders matching [query] in [roots], best first, at most [SEARCH_LIMIT]. A blank query finds
 * nothing; `~`, `~/…`, `./…` and absolute paths inside a root anchor the search there, and then
 * only there. Returns undefined once [cancelled] says a newer search has started.
 */
export async function searchFolders(
  query: string,
  roots: readonly SearchRoot[],
  cancelled: () => boolean,
): Promise<string[] | undefined> {
  const prepared = await Promise.all(roots.map((root) => prepare(query, root, cancelled)));
  const searches = prepared.filter((search) => search !== undefined);
  // `~/src` names a folder in home; reading it as plain text elsewhere would only add noise.
  const anchored = searches.some((search) => search.plan.isPathQuery);
  const exact: string[] = [];
  const ranked: Ranked[] = [];
  for (const search of searches) {
    if (anchored && !search.plan.isPathQuery) continue;
    const found = search.plan.isPathQuery ? await findExact(search) : undefined;
    if (found !== undefined) exact.push(found);
    ranked.push(...(await searchCandidates(search)));
    if (cancelled()) return undefined;
  }
  return [...new Set([...exact, ...sortUnique(ranked)])].slice(0, SEARCH_LIMIT);
}

async function prepare(query: string, root: SearchRoot, cancelled: () => boolean): Promise<Search | undefined> {
  const resolvedRoot = await resolveFolder(root.path);
  if (resolvedRoot === undefined) return undefined;
  const plan = parseQuery(query, root, resolvedRoot);
  return plan && { root: root.path, resolvedRoot, plan, cancelled };
}

async function searchCandidates(search: Search): Promise<Ranked[]> {
  if (search.plan.isPathQuery && !search.plan.normalizedQuery) return searchChildren(search);
  if (!search.plan.isPathQuery || !search.plan.parentPart) return searchTree(search);
  // A typed parent such as `~/Developer/pas` anchors the walk under `~/Developer`. A parent that
  // does not exist falls back to matching the typed path against the whole tree.
  return searchTree(search, await resolveTypedParent(search));
}

async function resolveTypedParent(search: Search): Promise<WalkStart | undefined> {
  const visiblePath = path.resolve(search.root, search.plan.parentPart);
  const resolvedPath = await resolveFolder(visiblePath);
  return resolvedPath !== undefined && isInside(search.resolvedRoot, resolvedPath)
    ? { visiblePath, resolvedPath }
    : undefined;
}

async function findExact(search: Search): Promise<string | undefined> {
  if (!search.plan.normalizedQuery) return undefined;
  const visiblePath = path.resolve(search.root, search.plan.normalizedQuery);
  const resolvedPath = await resolveFolder(visiblePath);
  return resolvedPath !== undefined && isInside(search.resolvedRoot, resolvedPath) ? visiblePath : undefined;
}

/** The root's own folders, for a query that names the root itself (`~`, `./`). */
async function searchChildren(search: Search): Promise<Ranked[]> {
  return (await readFolders(search.resolvedRoot)).flatMap((folder) =>
    staysInsideRoot(folder, search.resolvedRoot) && isDiscoverable(folder)
      ? [rank({ ...folder, visiblePath: path.join(search.root, folder.name), depth: 1 }, search)]
      : [],
  );
}

async function searchTree(
  search: Search,
  start: WalkStart = { visiblePath: search.root, resolvedPath: search.resolvedRoot },
): Promise<Ranked[]> {
  const visited = new Set<string>([start.resolvedPath]);
  const branches = (await readFolders(start.resolvedPath)).flatMap((folder) =>
    staysInsideRoot(folder, search.resolvedRoot) && isDiscoverable(folder)
      ? [walkBranch({ ...folder, visiblePath: path.join(start.visiblePath, folder.name), depth: 1 }, search, visited)]
      : [],
  );
  const ranked: Ranked[] = [];
  let scanned = 0;
  const confidenceQuery = search.plan.normalizedQuery || search.plan.searchTerm;
  for await (const folder of roundRobin(branches)) {
    if (search.cancelled()) break;
    scanned += 1;
    const candidate = rank(folder, search);
    if (candidate.matchTier !== NO_MATCH_TIER) ranked.push(candidate);
    if (
      scanned >= MAX_FOLDERS_SCANNED ||
      (scanned >= CONFIDENT_RESULT_SCAN_THRESHOLD && hasConfidentResult(ranked, confidenceQuery))
    ) {
      break;
    }
  }
  return ranked;
}

async function* walkBranch(folder: Walked, search: Search, visited: Set<string>): AsyncGenerator<Walked> {
  yield folder;
  if (visited.has(folder.resolvedPath) || folder.depth >= MAX_DEPTH) return;
  visited.add(folder.resolvedPath);
  const branches = (await readFolders(folder.resolvedPath)).flatMap((child) =>
    staysInsideRoot(child, search.resolvedRoot) && isDiscoverable(child)
      ? [
          walkBranch(
            { ...child, visiblePath: path.join(folder.visiblePath, child.name), depth: folder.depth + 1 },
            search,
            visited,
          ),
        ]
      : [],
  );
  yield* roundRobin(branches);
}

/** Breadth-first across branches, so a deep tree can't starve its siblings. */
async function* roundRobin<T>(branches: AsyncGenerator<T>[]): AsyncGenerator<T> {
  let active = branches;
  while (active.length > 0) {
    const nextRound: AsyncGenerator<T>[] = [];
    for (const branch of active) {
      const next = await branch.next();
      if (!next.done) {
        nextRound.push(branch);
        yield next.value;
      }
    }
    active = nextRound;
  }
}

// Only a symlink can resolve outside the tree being walked. Every other child is its parent's
// path plus a name, and the parent was already proved inside the root.
function staysInsideRoot(folder: Folder, resolvedRoot: string): boolean {
  return !folder.viaSymlink || isInside(resolvedRoot, folder.resolvedPath);
}

function isDiscoverable(folder: Folder): boolean {
  return !folder.name.startsWith(".") && !IGNORED_FOLDER_NAMES.has(folder.name);
}

function rank(folder: Walked, search: Search): Ranked {
  const relative = relativePath(search.root, folder.visiblePath);
  const lowerPath = relative.toLowerCase();
  const query = (search.plan.isPathQuery ? search.plan.normalizedQuery : search.plan.searchTerm).toLowerCase();
  const segments = lowerPath === "." ? [] : lowerPath.split("/");
  const pathScore = query ? scorePathMatch(query, relative) : undefined;
  const fields = search.plan.isPathQuery
    ? rankPathMatch(pathScore)
    : rankTextMatch(query, lowerPath, pathScore, segments);
  return { path: folder.visiblePath, ...fields, depth: relative === "." ? 0 : segments.length };
}

function rankPathMatch(pathScore: MatchScore | undefined): RankFields {
  return {
    matchTier: pathScore ? Math.min(pathScore.tier, 4) : NO_MATCH_TIER,
    segmentIndex: NO_SEGMENT_INDEX,
    matchOffset: pathScore?.offset ?? NO_MATCH_OFFSET,
    fuzzyScore: pathScore?.spread ?? NO_FUZZY_SCORE,
  };
}

function rankTextMatch(
  query: string,
  lowerPath: string,
  pathScore: MatchScore | undefined,
  segments: string[],
): RankFields {
  const offset = lowerPath.indexOf(query);
  const fuzzyScore = scoreFuzzySubsequence(query, segments.at(-1) ?? "");
  const matchOffset = offset >= 0 ? offset : NO_MATCH_OFFSET;
  const segmentTiers: [number, (segment: string) => boolean][] = [
    [0, (segment) => segment === query],
    [1, (segment) => segment.startsWith(query)],
    [2, (segment) => segment.includes(query)],
  ];
  for (const [matchTier, matches] of segmentTiers) {
    const segmentIndex = segments.findIndex(matches);
    if (segmentIndex >= 0) return { matchTier, segmentIndex, matchOffset, fuzzyScore: fuzzyScore ?? NO_FUZZY_SCORE };
  }
  if (offset >= 0) {
    return { matchTier: 3, segmentIndex: NO_SEGMENT_INDEX, matchOffset, fuzzyScore: fuzzyScore ?? NO_FUZZY_SCORE };
  }
  if (fuzzyScore !== undefined) {
    return { matchTier: 4, segmentIndex: NO_SEGMENT_INDEX, matchOffset, fuzzyScore };
  }
  return rankPathMatch(pathScore);
}

function sortUnique(entries: Ranked[]): string[] {
  const unique = new Map<string, Ranked>();
  for (const entry of entries) {
    const existing = unique.get(entry.path);
    if (!existing || compareRank(entry, existing) < 0) unique.set(entry.path, entry);
  }
  return [...unique.values()].toSorted(compareRank).map((entry) => entry.path);
}

function compareRank(left: Ranked, right: Ranked): number {
  return (
    left.matchTier - right.matchTier ||
    left.segmentIndex - right.segmentIndex ||
    left.matchOffset - right.matchOffset ||
    left.fuzzyScore - right.fuzzyScore ||
    left.depth - right.depth ||
    left.path.localeCompare(right.path)
  );
}

function hasConfidentResult(entries: Ranked[], query: string): boolean {
  const maxFuzzyScore = query.length * MAX_CONFIDENT_FUZZY_SKIPS_PER_CHARACTER;
  return entries.some(
    (entry) => entry.matchTier < 4 || (entry.matchTier === 4 && entry.fuzzyScore <= maxFuzzyScore),
  );
}

function parseQuery(query: string, root: SearchRoot, resolvedRoot: string): QueryPlan | undefined {
  const typed = query.trim();
  let normalized = typed;
  let rooted = false;
  for (const alias of root.aliases) {
    if (normalized === alias || normalized.startsWith(`${alias}/`)) {
      rooted = true;
      normalized = normalized.slice(alias.length).replace(/^\/+/, "");
      break;
    }
  }
  if (path.isAbsolute(normalized)) {
    const browsesFolder = normalized.endsWith("/");
    const absolute = path.resolve(normalized);
    const queryRoot = [resolvedRoot, root.path].find((candidate) => isInside(candidate, absolute));
    if (queryRoot === undefined) return undefined;
    rooted = true;
    normalized = relativePath(queryRoot, absolute);
    if (browsesFolder && normalized !== ".") normalized = `${normalized}/`;
  }
  if (normalized.startsWith("./")) rooted = true;
  normalized = normalized.replace(/^\.\/+/, "").replace(/\/{2,}/g, "/");
  if (normalized === "." && (rooted || typed === ".")) normalized = "";
  if (!normalized) {
    return rooted || typed === "."
      ? { isPathQuery: true, parentPart: "", searchTerm: "", normalizedQuery: "" }
      : undefined;
  }
  const slash = normalized.lastIndexOf("/");
  return {
    isPathQuery: rooted,
    parentPart: rooted && slash >= 0 ? normalized.slice(0, slash) : "",
    searchTerm: rooted && slash >= 0 ? normalized.slice(slash + 1) : normalized,
    normalizedQuery: normalized,
  };
}

async function resolveFolder(target: string): Promise<string | undefined> {
  try {
    const resolved = await realpath(target);
    return (await stat(resolved)).isDirectory() ? resolved : undefined;
  } catch {
    return undefined;
  }
}

/** Subfolders by name, following links; unreadable folders have none. Listings are cached briefly. */
async function readFolders(directory: string): Promise<Folder[]> {
  const info = await stat(directory).catch(() => undefined);
  if (!info?.isDirectory()) return [];
  const cached = listCache.get(directory);
  let entries: ListCacheEntry["entries"];
  if (
    cached &&
    cached.expiresAt > Date.now() &&
    cached.modifiedAtMs === info.mtimeMs &&
    cached.changedAtMs === info.ctimeMs
  ) {
    entries = cached.entries;
  } else {
    const dirents = await readdir(directory, { withFileTypes: true }).catch(() => []);
    entries = dirents
      .flatMap((dirent) => {
        if (dirent.isDirectory()) return [{ name: dirent.name, link: false }];
        return dirent.isSymbolicLink() ? [{ name: dirent.name, link: true }] : [];
      })
      .toSorted((left, right) => left.name.localeCompare(right.name));
    listCache.set(directory, {
      expiresAt: Date.now() + LIST_CACHE_TTL_MS,
      modifiedAtMs: info.mtimeMs,
      changedAtMs: info.ctimeMs,
      entries,
    });
    // Reads already reject stale entries, so pruning only has to bound the map.
    while (listCache.size > LIST_CACHE_MAX_ENTRIES) {
      const oldest = listCache.keys().next();
      if (oldest.done) break;
      listCache.delete(oldest.value);
    }
  }
  const folders = await Promise.all(
    entries.map(async ({ name, link }): Promise<Folder | undefined> => {
      const visiblePath = path.join(directory, name);
      if (!link) return { name, resolvedPath: visiblePath, viaSymlink: false };
      const resolvedPath = await resolveFolder(visiblePath);
      return resolvedPath === undefined ? undefined : { name, resolvedPath, viaSymlink: true };
    }),
  );
  return folders.filter((folder) => folder !== undefined);
}

function isInside(root: string, candidate: string): boolean {
  const relative = path.relative(root, candidate);
  return relative === "" || (relative !== ".." && !relative.startsWith("../") && !path.isAbsolute(relative));
}

function relativePath(root: string, absolutePath: string): string {
  return path.relative(root, absolutePath) || ".";
}

function scoreFuzzySubsequence(query: string, candidate: string): number | undefined {
  let queryIndex = 0;
  let first = -1;
  let previous = -1;
  let gaps = 0;
  for (let index = 0; index < candidate.length && queryIndex < query.length; index += 1) {
    if (candidate[index] !== query[queryIndex]) continue;
    if (first < 0) first = index;
    if (previous >= 0) gaps += index - previous - 1;
    previous = index;
    queryIndex += 1;
  }
  return queryIndex === query.length && first >= 0 ? first + gaps : undefined;
}

// Paseo's text-match, without the typo tier its path matcher never asks for.

interface MatchScore {
  tier: number;
  offset: number;
  spread?: number;
}

const TIER_EXACT = 0;
const TIER_WHOLE_WORD = 1;
const TIER_PREFIX = 2;
const TIER_WORD_START = 3;
const TIER_SUBSTRING = 4;
const TIER_SUBSEQUENCE = 5;

function isWordBoundaryChar(char: string | undefined): boolean {
  return char === undefined || !/[a-z0-9]/.test(char);
}

function scoreSubstringMatch(query: string, text: string): MatchScore | undefined {
  let best: MatchScore | undefined;
  let position = 0;
  while (position <= text.length - query.length) {
    const found = text.indexOf(query, position);
    if (found === -1) break;
    const startsAtBoundary = found === 0 || isWordBoundaryChar(text[found - 1]);
    const endsAtBoundary = isWordBoundaryChar(text[found + query.length]);
    let tier = TIER_SUBSTRING;
    if (startsAtBoundary && endsAtBoundary) tier = TIER_WHOLE_WORD;
    else if (found === 0) tier = TIER_PREFIX;
    else if (startsAtBoundary) tier = TIER_WORD_START;
    if (!best || tier < best.tier || (tier === best.tier && found < best.offset)) best = { tier, offset: found };
    position = found + 1;
  }
  return best;
}

function scoreSubsequenceMatch(query: string, text: string): MatchScore | undefined {
  let queryIndex = 0;
  let firstIndex = -1;
  let lastIndex = -1;
  for (let textIndex = 0; textIndex < text.length && queryIndex < query.length; textIndex += 1) {
    const char = text.charAt(textIndex);
    if (/\s/u.test(char)) {
      queryIndex = 0;
      firstIndex = -1;
      lastIndex = -1;
      continue;
    }
    if (char !== query[queryIndex]) continue;
    if (firstIndex === -1) firstIndex = textIndex;
    lastIndex = textIndex;
    queryIndex += 1;
  }
  if (queryIndex !== query.length || firstIndex === -1) return undefined;
  return { tier: TIER_SUBSEQUENCE, offset: firstIndex, spread: lastIndex - firstIndex + 1 };
}

function scoreMatch(query: string, text: string): MatchScore | undefined {
  if (!query) return { tier: TIER_EXACT, offset: 0 };
  const lowerQuery = query.toLowerCase();
  const lowerText = text.toLowerCase();
  if (lowerText === lowerQuery) return { tier: TIER_EXACT, offset: 0 };
  return scoreSubstringMatch(lowerQuery, lowerText) ?? scoreSubsequenceMatch(lowerQuery, lowerText);
}

function compactText(value: string): { value: string; offsets: number[] } {
  let compact = "";
  const offsets: number[] = [];
  for (let index = 0; index < value.length; index += 1) {
    const char = value.charAt(index);
    if (!/[a-z0-9]/i.test(char)) continue;
    compact += char.toLowerCase();
    offsets.push(index);
  }
  return { value: compact, offsets };
}

/** Matches a query against a whole displayed path, separators included, then with them dropped. */
function scorePathMatch(query: string, target: string): MatchScore | undefined {
  const direct = scoreMatch(query, target);
  if (direct) return direct;
  const compactQuery = compactText(query);
  const compactPath = compactText(target);
  if (!compactQuery.value || !compactPath.value) return undefined;
  const compactScore = scoreMatch(compactQuery.value, compactPath.value);
  if (!compactScore) return undefined;
  return { ...compactScore, offset: compactPath.offsets[compactScore.offset] ?? compactScore.offset };
}
