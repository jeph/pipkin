import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { access, copyFile, lstat, mkdir, mkdtemp, readFile, realpath, rm, stat } from "node:fs/promises";
import os from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { parsePatch, type StructuredPatch } from "diff";
import type { HostText } from "../env.js";
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";

/** Paseo's limits: one file's diff text, then the whole diff's. */
const FILE_LIMIT = 1024 * 1024;
const TOTAL_LIMIT = 2 * 1024 * 1024;
/** Paseo's cap on `git diff --numstat` output; files past it show no counts. */
const NUMSTAT_LIMIT = 2 * 1024 * 1024;
/** The most commits a commits reply lists; `total` still counts them all. */
const COMMITS_LIMIT = 500;
/** Keeps a reply well under the app's 16 MiB line limit. */
const REPLY_LIMIT = 8 * 1024 * 1024;
/** How long one git run may take; tests shorten it so they needn't wait. */
const GIT_TIMEOUT_MS = Number(process.env.PIPKIN_GIT_TIMEOUT_MS ?? 30_000);
/** Enough of git's stderr to explain a failure. */
const STDERR_LIMIT = 64 * 1024;

/**
 * No optional lock (a viewer must not block the user's own git), no credential prompt with nobody to
 * answer it, and pathspecs taken literally so a file name is never read as a glob.
 */
const GIT_ENV = { GIT_OPTIONAL_LOCKS: "0", GIT_TERMINAL_PROMPT: "0", GIT_LITERAL_PATHSPECS: "1" };
/**
 * Not hardening: these keep the user's config (colour, external diff tools, prefixes, submodule logs)
 * from changing the output this file parses.
 */
const DIFF_FLAGS = ["--no-color", "--no-ext-diff", "-M"];
const PATCH_FLAGS = [...DIFF_FLAGS, "--src-prefix=a/", "--dst-prefix=b/", "--submodule=short"];
const SECTION_MARKER = Buffer.from("diff --git ");
const SHA = /^[0-9a-f]{4,64}$/i;

type ErrorCode = "not_repo" | "no_git" | "failed" | "commit_not_found";

/** A failure the reply reports as `{error, code, errorI18n}`; git's own stderr has no [i18n]. */
class GitError extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
    readonly i18n?: HostText,
  ) {
    super(message);
  }
}

/** The diff can't fit in a reply; reported as `tooLarge` rather than an error. */
class TooLarge extends Error {}

/** Git ran past [GIT_TIMEOUT_MS]. A diff that slow is reported as `tooLarge`; anything else as a failure. */
class TimedOut extends GitError {
  constructor(command: string | undefined) {
    const gitCommand = `git ${command ?? ""}`;
    const seconds = GIT_TIMEOUT_MS / 1000;
    super("failed", `${gitCommand} took longer than ${String(seconds)} seconds.`, {
      key: "git_timed_out",
      args: { command: gitCommand, seconds },
    });
  }
}

/** A repository the requests run git in. */
interface Repo {
  binary: string;
  root: string;
  /** `-c safe.directory=…` for the root and its realpath, so repos on shared storage are readable. */
  safeArgs: string[];
  signal: AbortSignal;
}

interface RunOptions {
  env?: Record<string, string>;
  /** Resolve on any exit code instead of failing on a non-zero one. */
  anyExit?: boolean;
  /** Fail with [TooLarge] once stdout passes this many bytes. */
  stdoutLimit?: number;
  /** Keep only this many bytes of stdout, then kill git and resolve with `truncated`. */
  truncateAt?: number;
  /** Takes stdout as it arrives instead of collecting it. */
  onStdout?: (chunk: Buffer) => void;
}

interface RunResult {
  exitCode: number | null;
  stdout: string;
  /** Whether [RunOptions.truncateAt] cut stdout short. */
  truncated: boolean;
}

async function isExecutable(path: string): Promise<boolean> {
  try {
    await access(path, constants.X_OK);
    return (await stat(path)).isFile();
  } catch {
    return false;
  }
}

/**
 * Runs git in [repo], killed after [GIT_TIMEOUT_MS] or when the app hangs up. Git runs in a process group of its
 * own and the whole group is killed, since a helper it started, such as a textconv filter, would otherwise outlive
 * it and keep its output open, so the run would never end.
 */
function run(repo: Repo, args: string[], options: RunOptions = {}): Promise<RunResult> {
  const { promise, resolve: done, reject } = Promise.withResolvers<RunResult>();
  if (repo.signal.aborted) {
    reject(repo.signal.reason);
    return promise;
  }
  const child = spawn(repo.binary, [...repo.safeArgs, ...args], {
    cwd: repo.root,
    env: { ...process.env, ...GIT_ENV, ...options.env },
    stdio: ["ignore", "pipe", "pipe"],
    detached: true,
  });
  let killedFor: "abort" | "timeout" | "overflow" | "truncated" | undefined;
  const kill = (reason: NonNullable<typeof killedFor>): void => {
    killedFor ??= reason;
    if (child.pid === undefined) return;
    try {
      process.kill(-child.pid, "SIGKILL");
    } catch {
      // The whole group has already exited.
    }
  };
  const timer = setTimeout(() => kill("timeout"), GIT_TIMEOUT_MS);
  const onAbort = (): void => kill("abort");
  repo.signal.addEventListener("abort", onAbort, { once: true });

  const stdout: Buffer[] = [];
  let stdoutBytes = 0;
  child.stdout.on("data", (chunk: Buffer) => {
    if (killedFor !== undefined) return;
    if (options.onStdout !== undefined) {
      options.onStdout(chunk);
      return;
    }
    if (options.truncateAt !== undefined && stdoutBytes + chunk.length > options.truncateAt) {
      stdout.push(chunk.subarray(0, options.truncateAt - stdoutBytes));
      kill("truncated");
      return;
    }
    stdoutBytes += chunk.length;
    if (options.stdoutLimit !== undefined && stdoutBytes > options.stdoutLimit) {
      kill("overflow");
      return;
    }
    stdout.push(chunk);
  });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk: string) => {
    if (stderr.length < STDERR_LIMIT) stderr += chunk;
  });

  const settle = (): void => {
    clearTimeout(timer);
    repo.signal.removeEventListener("abort", onAbort);
  };
  child.on("error", (error) => {
    settle();
    const missing = "code" in error && (error.code === "ENOENT" || error.code === "EACCES");
    reject(missing ? new GitError("no_git", "git isn't installed in Termux.", { key: "git_missing" }) : error);
  });
  child.on("close", (exitCode) => {
    settle();
    if (killedFor === "abort") return reject(repo.signal.reason);
    if (killedFor === "timeout") return reject(new TimedOut(args[0]));
    if (killedFor === "overflow") return reject(new TooLarge());
    const truncated = killedFor === "truncated";
    const result = { exitCode, stdout: Buffer.concat(stdout).toString("utf8"), truncated };
    if (truncated || exitCode === 0 || options.anyExit === true) return done(result);
    const stderrText = stderr.trim();
    if (stderrText !== "") return reject(new GitError("failed", stderrText));
    const command = `git ${args[0]}`;
    const exit = String(exitCode);
    reject(new GitError("failed", `${command} exited with ${exit}.`, { key: "git_exited", args: { command, exit } }));
  });
  return promise;
}

/** Whether `<root>/.git` is a folder, or a file as in linked worktrees and submodule checkouts. */
async function hasDotGit(root: string): Promise<boolean> {
  try {
    const stats = await lstat(join(root, ".git"));
    return stats.isDirectory() || stats.isFile();
  } catch {
    return false;
  }
}

async function openRepo(ctx: ModeContext, root: string): Promise<Repo> {
  const binary = join(ctx.env.prefix, "bin", "git");
  if (!(await isExecutable(binary))) throw new GitError("no_git", "git isn't installed in Termux.", { key: "git_missing" });
  let realRoot: string;
  try {
    if (!isAbsolute(root)) throw new Error();
    realRoot = await realpath(root);
  } catch {
    throw new GitError("not_repo", `Folder not found: ${root}`, { key: "folder_not_found", args: { path: root } });
  }
  const safeArgs = ["-c", `safe.directory=${root}`];
  if (realRoot !== root) safeArgs.push("-c", `safe.directory=${realRoot}`);
  return { binary, root, safeArgs, signal: ctx.signal };
}

/**
 * The checked-out branch's name. Detached, Paseo's `getCurrentBranch` instead names the branch a rebase
 * in progress is rewriting (`rebase-merge/head-name`, then `rebase-apply/head-name`, less `refs/heads/`), else null.
 */
async function currentBranch(repo: Repo): Promise<string | null> {
  const { exitCode, stdout } = await run(repo, ["symbolic-ref", "--quiet", "--short", "HEAD"], { anyExit: true });
  if (exitCode === 0 && stdout.trim() !== "") return stdout.trim();
  const paths = (await run(repo, ["rev-parse", "--git-path", "rebase-merge/head-name", "--git-path", "rebase-apply/head-name"])).stdout.split("\n");
  for (const path of paths.slice(0, 2)) {
    let headName: string;
    try {
      headName = (await readFile(resolve(repo.root, path), "utf8")).trim();
    } catch {
      continue;
    }
    const name = headName.startsWith("refs/heads/") ? headName.slice("refs/heads/".length) : headName;
    if (name !== "") return name;
  }
  return null;
}

/** HEAD's commit; null while the branch is unborn. */
async function headCommit(repo: Repo): Promise<string | null> {
  const { exitCode, stdout } = await run(repo, ["rev-parse", "--verify", "--quiet", "HEAD^{commit}"], { anyExit: true });
  return exitCode === 0 && stdout.trim() !== "" ? stdout.trim() : null;
}

/** The empty tree's id in this repo's hash, for diffing a commit or work tree with no parent. */
async function emptyTree(repo: Repo): Promise<string> {
  return (await run(repo, ["hash-object", "-t", "tree", "/dev/null"])).stdout.trim();
}

interface GitPaths {
  index: string;
  objects: string;
}

/** Fails with `not_repo` unless git can read [repo]. Returns the index file's and object store's absolute paths. */
async function requireRepo(repo: Repo): Promise<GitPaths> {
  try {
    const { stdout } = await run(repo, ["rev-parse", "--show-toplevel", "--git-path", "index", "--git-path", "objects"]);
    const lines = stdout.split("\n");
    return { index: resolve(repo.root, lines[1] ?? ""), objects: resolve(repo.root, lines[2] ?? "") };
  } catch (error) {
    if (error instanceof GitError && error.code === "failed") throw new GitError("not_repo", error.message, error.i18n);
    throw error;
  }
}

async function refExists(repo: Repo, fullRef: string): Promise<boolean> {
  return (await run(repo, ["show-ref", "--verify", "--quiet", fullRef], { anyExit: true })).exitCode === 0;
}

/** Paseo's display-only branch name of a ref: `refs/heads/x`, `refs/remotes/<remote>/x` and `origin/x` all name `x`. */
function branchNameFromRef(ref: string): string {
  if (ref.startsWith("refs/heads/")) return ref.slice("refs/heads/".length);
  if (ref.startsWith("refs/remotes/")) {
    const remainder = ref.slice("refs/remotes/".length);
    const separator = remainder.indexOf("/");
    return separator === -1 ? remainder : remainder.slice(separator + 1);
  }
  return ref.startsWith("origin/") ? ref.slice("origin/".length) : ref;
}

/**
 * The repo's base branch, Paseo's way: `origin/HEAD`'s branch, as a local branch when one exists and
 * as `origin/<name>` otherwise; failing that local `main`, then `master`; else none.
 */
async function defaultBranch(repo: Repo): Promise<string | null> {
  const originHead = await run(repo, ["symbolic-ref", "--quiet", "refs/remotes/origin/HEAD"], { anyExit: true });
  const ref = originHead.exitCode === 0 ? originHead.stdout.trim() : "";
  if (ref !== "") {
    const remoteName = ref.replace(/^refs\/remotes\//, "");
    const localName = remoteName.startsWith("origin/") ? remoteName.slice("origin/".length) : remoteName;
    return (await refExists(repo, `refs/heads/${localName}`)) ? localName : remoteName;
  }
  const [hasMain, hasMaster] = await Promise.all([refExists(repo, "refs/heads/main"), refExists(repo, "refs/heads/master")]);
  if (hasMain) return "main";
  return hasMaster ? "master" : null;
}

/**
 * The ref committed changes are compared with, both the diff, as in Paseo, and the commits list: `origin/<base>`
 * whenever it exists, else the local branch.
 */
async function comparisonRef(repo: Repo, base: string): Promise<string | null> {
  const name = branchNameFromRef(base);
  const [hasLocal, hasOrigin] = await Promise.all([refExists(repo, `refs/heads/${name}`), refExists(repo, `refs/remotes/origin/${name}`)]);
  if (hasOrigin) return `origin/${name}`;
  return hasLocal ? name : null;
}

async function mergeBase(repo: Repo, ref: string): Promise<string | null> {
  const { exitCode, stdout } = await run(repo, ["merge-base", ref, "HEAD"], { anyExit: true });
  return exitCode === 0 && stdout.trim() !== "" ? stdout.trim() : null;
}

type Change = "added" | "deleted" | "modified" | "renamed" | "copied";
const CHANGES: Record<string, Change> = { A: "added", D: "deleted", R: "renamed", C: "copied" };

interface FileStat {
  path: string;
  oldPath?: string;
  /** git's status letter. */
  status: string;
  additions: number;
  deletions: number;
  binary: boolean;
}

/**
 * Parses `git diff --raw -z`: per file `:modes shas status`, then one path, or the old and new path for
 * renames and copies.
 */
function parseRaw(output: string): Map<string, FileStat> {
  const tokens = output.split("\0");
  const byPath = new Map<string, FileStat>();
  for (let i = 0; tokens[i]?.startsWith(":") === true; i += 1) {
    const status = tokens[i]?.split(" ")[4]?.[0] ?? "M";
    const paired = status === "R" || status === "C";
    const oldPath = paired ? tokens[++i] : undefined;
    const path = tokens[++i];
    if (path === undefined) break;
    // An unmerged path can be listed twice; its content change is the useful record.
    if (byPath.has(path) && status === "U") continue;
    byPath.set(path, { path, ...(oldPath === undefined ? {} : { oldPath }), status, additions: 0, deletions: 0, binary: false });
  }
  return byPath;
}

/**
 * Fills [files]' counts from `git diff --numstat -z`: `added\tdeleted\tpath`, or `added\tdeleted\t` then
 * the old and new path; `-` counts mean binary. Output [truncated] at Paseo's limit loses its last,
 * partial record, and the files past the cut keep zero counts, as in Paseo.
 */
function applyNumstat(files: Map<string, FileStat>, output: string, truncated: boolean): void {
  const tokens = output.split("\0");
  if (truncated) tokens.pop();
  for (let i = 0; i < tokens.length; i += 1) {
    const record = tokens[i] ?? "";
    const firstTab = record.indexOf("\t");
    const secondTab = record.indexOf("\t", firstTab + 1);
    if (firstTab === -1 || secondTab === -1) continue;
    let path = record.slice(secondTab + 1);
    if (path === "") {
      i += 2;
      path = tokens[i] ?? "";
    }
    const file = files.get(path);
    if (file === undefined) continue;
    const added = record.slice(0, firstTab);
    const deleted = record.slice(firstTab + 1, secondTab);
    file.binary = added === "-" && deleted === "-";
    file.additions = file.binary ? 0 : Number(added);
    file.deletions = file.binary ? 0 : Number(deleted);
  }
}

/** The work-tree-side path a parsed patch is about: the new path, or the old one for a deletion. */
function patchPath(patch: StructuredPatch): string | undefined {
  const name = patch.newFileName === undefined || patch.newFileName === "/dev/null" ? patch.oldFileName : patch.newFileName;
  if (name === undefined || name === "/dev/null") return undefined;
  return name.startsWith("a/") || name.startsWith("b/") ? name.slice(2) : name;
}

/** A section's path, from its headers alone (a section cut short may end mid-hunk). */
function sectionPath(text: string): string | undefined {
  const hunkStart = text.indexOf("\n@@");
  try {
    const [patch] = parsePatch(hunkStart === -1 ? text : text.slice(0, hunkStart + 1));
    return patch === undefined ? undefined : patchPath(patch);
  } catch {
    return undefined;
  }
}

/** git's note in place of a binary file's hunks; content lines always start with a prefix character. */
const BINARY_NOTE = /^(Binary files .* differ|GIT binary patch)$/m;

/** One file's part of the patch: one section, or two for a type change (file to symlink, say). */
interface PatchFile {
  path: string;
  sections: string[];
  bytes: number;
  /** Passed [FILE_LIMIT], so its text was dropped. */
  overflowed: boolean;
  binary: boolean;
}

/**
 * Splits a streamed patch into per-file sections at `diff --git ` line starts and applies Paseo's limits
 * file by file, in git's path order (`processTrackedChanges`): a file whose diff text passes [FILE_LIMIT]
 * is too large; any other is kept if it fits in what is left of [TOTAL_LIMIT], else it is too large and
 * later, smaller files may still fit. Binary files, which Paseo never diffs as text, use none of the
 * budget. Memory stays bounded however big the patch is: kept text never passes [TOTAL_LIMIT], and a
 * section stops being buffered once it passes [FILE_LIMIT].
 */
class PatchSections {
  readonly kept: string[] = [];
  readonly tooLarge = new Set<string>();
  private keptBytes = 0;
  private file: PatchFile | undefined;
  private section: Buffer[] | undefined;
  private sectionBytes = 0;
  private overflowed = false;
  /** The current section's path, read when it passed [FILE_LIMIT]. */
  private overflowPath: string | undefined;
  /** The current line's first bytes, held until they show whether it starts a section. */
  private lineStart: Buffer[] = [];
  private lineStartBytes = 0;
  private atLineStart = true;

  /** Takes the next chunk of patch. */
  push(chunk: Buffer): void {
    let position = 0;
    while (position < chunk.length) {
      const newline = chunk.indexOf(10, position);
      const lineEnd = newline === -1 ? chunk.length : newline + 1;
      if (this.atLineStart) {
        const end = Math.min(lineEnd, position + SECTION_MARKER.length - this.lineStartBytes);
        this.lineStart.push(chunk.subarray(position, end));
        this.lineStartBytes += end - position;
        position = end;
        const lineEnded = chunk[end - 1] === 10;
        if (this.lineStartBytes < SECTION_MARKER.length && !lineEnded) continue;
        const head = Buffer.concat(this.lineStart);
        this.lineStart = [];
        this.lineStartBytes = 0;
        if (head.subarray(0, SECTION_MARKER.length).equals(SECTION_MARKER)) this.startSection();
        this.append(head);
        this.atLineStart = lineEnded;
        continue;
      }
      this.append(chunk.subarray(position, lineEnd));
      position = lineEnd;
      this.atLineStart = newline !== -1;
    }
  }

  /** Ends the last section and file once git has exited. */
  finish(): void {
    if (this.lineStartBytes > 0) this.append(Buffer.concat(this.lineStart));
    this.endSection();
    this.endFile();
  }

  private startSection(): void {
    this.endSection();
    this.section = [];
    this.sectionBytes = 0;
    this.overflowed = false;
    this.overflowPath = undefined;
  }

  /** Adds the finished section to its file, ending the previous file when the path changes. */
  private endSection(): void {
    const section = this.section;
    if (section === undefined) return;
    this.section = undefined;
    const text = this.overflowed ? "" : Buffer.concat(section).toString("utf8");
    const path = this.overflowed ? this.overflowPath : sectionPath(text);
    if (path === undefined) return;
    if (this.file?.path !== path) {
      this.endFile();
      this.file = { path, sections: [], bytes: 0, overflowed: false, binary: false };
    }
    const file = this.file;
    file.bytes += this.sectionBytes;
    file.overflowed ||= this.overflowed;
    file.binary ||= BINARY_NOTE.test(text);
    if (!file.overflowed) file.sections.push(text);
  }

  private endFile(): void {
    const file = this.file;
    this.file = undefined;
    if (file === undefined || file.binary) return;
    if (file.overflowed || file.bytes > FILE_LIMIT || this.keptBytes + file.bytes > TOTAL_LIMIT) {
      this.tooLarge.add(file.path);
      return;
    }
    this.kept.push(...file.sections);
    this.keptBytes += file.bytes;
  }

  private append(bytes: Buffer): void {
    // Text before the first section (such as `* Unmerged path`) belongs to no file.
    if (this.section === undefined || this.overflowed) return;
    this.section.push(bytes);
    this.sectionBytes += bytes.length;
    if (this.sectionBytes > FILE_LIMIT) {
      this.overflowPath = sectionPath(Buffer.concat(this.section).toString("utf8"));
      this.section = [];
      this.overflowed = true;
    }
  }
}

interface Hunk {
  header: string;
  oldStart: number;
  newStart: number;
  lines: string[];
}

/** Each kept section's hunks by path; a type change (file to symlink, say) is two sections for one path. */
function parseSections(sections: string[]): Map<string, Hunk[]> {
  const hunksByPath = new Map<string, Hunk[]>();
  for (const text of sections) {
    // jsdiff keeps no hunk header text, so the `@@` lines are read in order alongside it.
    const headers = text.split("\n").filter((line) => line.startsWith("@@ "));
    let headerIndex = 0;
    for (const patch of parsePatch(text)) {
      const path = patchPath(patch);
      const hunks = patch.hunks.map((hunk) => ({
        header: (headers[headerIndex++] ?? "").replace(/\r$/, ""),
        oldStart: hunk.oldStart,
        newStart: hunk.newStart,
        lines: hunk.lines,
      }));
      if (path === undefined) continue;
      hunksByPath.set(path, [...(hunksByPath.get(path) ?? []), ...hunks]);
    }
  }
  return hunksByPath;
}

type DiffBody = { files: JsonObject[] } | { tooLarge: true; files: [] };

/**
 * The files changed between git diff's [revisions] (one revision diffs against the work tree), with
 * Paseo's limits. [env] carries the temporary index and object folder for work tree diffs.
 *
 * Three git runs at once, however many files: the file list (`--raw`, which fails the diff as too large
 * past a reply's worth, since every entry is shorter than its place in the reply), the counts
 * (`--numstat`, cut at Paseo's limit) and the patch, streamed through [PatchSections]. Paseo runs git
 * per batch of 64 files instead, but its limits only need each file's diff text in path order, which
 * the one patch stream gives in the same sizes.
 */
async function diffFiles(repo: Repo, revisions: string[], ignoreWhitespace: boolean, env: Record<string, string> = {}): Promise<DiffBody> {
  const whitespace = ignoreWhitespace ? ["-w"] : [];
  const stop = new AbortController();
  const stoppable = { ...repo, signal: AbortSignal.any([repo.signal, stop.signal]) };
  const sections = new PatchSections();
  const listRun = run(repo, ["diff", ...DIFF_FLAGS, ...whitespace, "--raw", "-z", ...revisions, "--"], { env, stdoutLimit: REPLY_LIMIT });
  const numstatRun = run(stoppable, ["diff", ...DIFF_FLAGS, ...whitespace, "--numstat", "-z", ...revisions, "--"], { env, truncateAt: NUMSTAT_LIMIT });
  const patchRun = run(stoppable, ["diff", ...PATCH_FLAGS, ...whitespace, ...revisions, "--"], { env, onStdout: (chunk) => sections.push(chunk) });
  void listRun.catch(() => stop.abort());
  const [list, numstat, patch] = await Promise.allSettled([listRun, numstatRun, patchRun]);
  // Past a reply's worth, or more than git could produce in time: either way too large to preview.
  const outsized = [list, numstat, patch].some(
    (result) => result.status === "rejected" && (result.reason instanceof TooLarge || result.reason instanceof TimedOut),
  );
  if (outsized) return { tooLarge: true, files: [] };
  if (list.status === "rejected") throw list.reason;
  if (numstat.status === "rejected") throw numstat.reason;
  if (patch.status === "rejected") throw patch.reason;
  const stats = parseRaw(list.value.stdout);
  applyNumstat(stats, numstat.value.stdout, numstat.value.truncated);
  sections.finish();
  const hunksByPath = parseSections(sections.kept);

  const files: JsonObject[] = [];
  for (const record of [...stats.values()].toSorted((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))) {
    // Paseo drops modifications that were whitespace only, so hiding whitespace hides them entirely.
    if (ignoreWhitespace && record.status === "M" && !record.binary && record.additions === 0 && record.deletions === 0) continue;
    const file: JsonObject = {
      path: record.path,
      ...(record.oldPath === undefined ? {} : { oldPath: record.oldPath }),
      change: CHANGES[record.status] ?? "modified",
      additions: record.additions,
      deletions: record.deletions,
    };
    const hunks = hunksByPath.get(record.path);
    if (record.binary) file.kind = "binary";
    else if (hunks !== undefined) Object.assign(file, { kind: "text", hunks });
    else if (sections.tooLarge.has(record.path)) file.kind = "too_large";
    else Object.assign(file, { kind: "text", hunks: [] });
    files.push(file);
  }
  return { files };
}

/**
 * HEAD (or the empty tree while unborn) against the work tree, untracked files included: they are
 * added as intent-to-add to a temporary copy of the index. The repo itself is never written: the
 * objects `git add` writes (the empty blob) go to a temporary object folder that reads the repo's own
 * object store as an alternate, so a read-only store works too.
 */
async function workTreeDiff(repo: Repo, paths: GitPaths, ignoreWhitespace: boolean): Promise<DiffBody> {
  const from = (await headCommit(repo)) ?? (await emptyTree(repo));
  const dir = await mkdtemp(join(os.tmpdir(), "pipkin-git-"));
  try {
    const tempIndex = join(dir, "index");
    const tempObjects = join(dir, "objects");
    await mkdir(tempObjects);
    try {
      await copyFile(paths.index, tempIndex);
    } catch (error) {
      // A fresh repo has no index until the first `git add`.
      if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) throw error;
    }
    // The variable is a `:`-separated list, in which git reads a double-quoted entry C-style.
    const alternate = /[:"]/.test(paths.objects) ? JSON.stringify(paths.objects) : paths.objects;
    const env = { GIT_INDEX_FILE: tempIndex, GIT_OBJECT_DIRECTORY: tempObjects, GIT_ALTERNATE_OBJECT_DIRECTORIES: alternate };
    await run(repo, ["add", "--intent-to-add", "--all"], { env });
    return await diffFiles(repo, [from], ignoreWhitespace, env);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

/**
 * Committed changes, Paseo's way (`resolveCheckoutDiffRefs`): the merge-base with the base branch
 * against HEAD, or the base branch itself when the histories are unrelated; nothing without a base or
 * while HEAD is unborn.
 */
async function branchDiff(repo: Repo, base: string | null, ignoreWhitespace: boolean): Promise<DiffBody> {
  const comparison = base === null ? null : await comparisonRef(repo, base);
  if (comparison === null || (await headCommit(repo)) === null) return { files: [] };
  return diffFiles(repo, [(await mergeBase(repo, comparison)) ?? comparison, "HEAD"], ignoreWhitespace);
}

/** [body] with its fingerprint, or `unchanged` alone when the app already has it. */
function fingerprinted(body: JsonObject, known: unknown): JsonObject {
  const fingerprint = createHash("sha256").update(JSON.stringify(body)).digest("hex");
  return fingerprint === known ? { fingerprint, unchanged: true } : { fingerprint, ...body };
}

function failure(error: unknown): JsonObject {
  if (error instanceof GitError) return { error: error.message, code: error.code, errorI18n: error.i18n };
  return { error: error instanceof Error ? error.message : String(error), code: "failed" };
}

async function detect(ctx: ModeContext, root: string, trusted: boolean): Promise<JsonObject> {
  const hasGit = await isExecutable(join(ctx.env.prefix, "bin", "git"));
  const dotGit = isAbsolute(root) && (await hasDotGit(root));
  if (!trusted || !hasGit || !dotGit) return { repo: dotGit, git: hasGit };
  try {
    const repo = await openRepo(ctx, root);
    const { stdout } = await run(repo, ["rev-parse", "--show-toplevel"]);
    return { repo: stdout.replace(/\n$/, "") === (await realpath(root)), git: true };
  } catch (error) {
    if (ctx.signal.aborted) throw error;
    return { repo: false, git: true };
  }
}

async function changes(ctx: ModeContext, root: string, mode: string, ignoreWhitespace: boolean, known: unknown): Promise<JsonObject> {
  try {
    const repo = await openRepo(ctx, root);
    const paths = await requireRepo(repo);
    const [branch, baseRef] = await Promise.all([currentBranch(repo), defaultBranch(repo)]);
    const diff = mode === "base" ? await branchDiff(repo, baseRef, ignoreWhitespace) : await workTreeDiff(repo, paths, ignoreWhitespace);
    let body: JsonObject = { branch, baseRef, ...diff };
    if (Buffer.byteLength(JSON.stringify(body)) > REPLY_LIMIT) body = { branch, baseRef, tooLarge: true, files: [] };
    return fingerprinted(body, known);
  } catch (error) {
    if (ctx.signal.aborted) throw error;
    return failure(error);
  }
}

/**
 * The commits ahead of the ref the committed diff compares with, on any branch including the base one:
 * `git log <comparison>..HEAD`, the newest [COMMITS_LIMIT] of `total`. None while detached (outside a rebase)
 * or unborn.
 */
async function commits(ctx: ModeContext, root: string, known: unknown): Promise<JsonObject> {
  try {
    const repo = await openRepo(ctx, root);
    await requireRepo(repo);
    const [branch, head, base] = await Promise.all([currentBranch(repo), headCommit(repo), defaultBranch(repo)]);
    const comparison = branch === null || head === null || base === null ? null : await comparisonRef(repo, base);
    if (comparison === null) return fingerprinted({ baseRef: comparison, total: 0, commits: [] }, known);
    const range = `${comparison}..HEAD`;
    const [log, count] = await Promise.all([
      run(repo, ["log", "--no-color", "--no-show-signature", "-z", `--max-count=${String(COMMITS_LIMIT)}`, "--format=%H%x00%at%x00%s", range, "--"]),
      run(repo, ["rev-list", "--count", range, "--"]),
    ]);
    const fields = log.stdout.split("\0");
    const list: JsonObject[] = [];
    for (let i = 0; i + 2 < fields.length; i += 3) {
      list.push({ sha: fields[i] ?? "", subject: fields[i + 2] ?? "", authorTime: Number(fields[i + 1]) });
    }
    return fingerprinted({ baseRef: comparison, total: Number(count.stdout.trim()), commits: list }, known);
  } catch (error) {
    if (ctx.signal.aborted) throw error;
    return failure(error);
  }
}

/** A commit against its first parent, or against the empty tree for a root commit. */
async function commitDiff(ctx: ModeContext, root: string, sha: string, ignoreWhitespace: boolean): Promise<JsonObject> {
  try {
    const repo = await openRepo(ctx, root);
    await requireRepo(repo);
    const notFound = new GitError("commit_not_found", `Commit ${sha} not found.`);
    if (!SHA.test(sha)) throw notFound;
    const commit = await run(repo, ["rev-parse", "--verify", "--quiet", `${sha}^{commit}`], { anyExit: true });
    if (commit.exitCode !== 0) throw notFound;
    const full = commit.stdout.trim();
    const parent = await run(repo, ["rev-parse", "--verify", "--quiet", `${full}^1`], { anyExit: true });
    const from = parent.exitCode === 0 ? parent.stdout.trim() : await emptyTree(repo);
    const body: JsonObject = { ...(await diffFiles(repo, [from, full], ignoreWhitespace)) };
    return Buffer.byteLength(JSON.stringify(body)) > REPLY_LIMIT ? { tooLarge: true, files: [] } : body;
  } catch (error) {
    if (ctx.signal.aborted) throw error;
    return failure(error);
  }
}

/**
 * Long-lived, read-only git for the app's Changes tab: sends `pipkin_git_ready`, then answers
 * concurrently, echoing each request's `id`, until the app hangs up. Every request names the absolute
 * workspace `root`; git runs there with the repo's own config, killed after 30 s or on hang-up.
 * - `pipkin_git_detect {trusted}` with `pipkin_git_detected {repo, git}`: whether `$PREFIX/bin/git` is
 *   executable, and whether `.git` sits in the root (for trusted roots, also that git reads the root as
 *   its top level). Untrusted roots never run git.
 * - `pipkin_git_changes {mode: "uncommitted" | "base", ignoreWhitespace, known?}` with
 *   `pipkin_git_changes_result {fingerprint, branch, baseRef, files, tooLarge?}`, or
 *   `{fingerprint, unchanged: true}` when `known` is the current fingerprint.
 * - `pipkin_git_commits {known?}` with `pipkin_git_commits_result {fingerprint, baseRef, total, commits}`
 *   (at most 500 `{sha, subject, authorTime}`, newest first, of `total`), or `{fingerprint, unchanged: true}`.
 * - `pipkin_git_commit_diff {sha, ignoreWhitespace}` with `pipkin_git_commit_diff_result {files, tooLarge?}`.
 * Failures are `{error, code, errorI18n?}` in the reply: `not_repo`, `no_git`, `commit_not_found` or `failed`.
 */
export async function git(ctx: ModeContext): Promise<JsonObject> {
  ctx.conn.send({ type: "pipkin_git_ready" });
  // Returning exits the process, so a hang-up first waits for killed requests to remove their temporary index.
  const inFlight = new Set<Promise<void>>();
  const reply = (type: string, id: number, result: Promise<JsonObject>): void => {
    const sent = result.then(
      (fields) => ctx.conn.send({ type, id, ...fields }),
      // Only a hang-up rejects, and then there is nobody to tell.
      () => undefined,
    );
    inFlight.add(sent);
    void sent.finally(() => inFlight.delete(sent));
  };
  ctx.conn.listen((message) => {
    const { id, root, ignoreWhitespace } = message;
    if (typeof id !== "number" || typeof root !== "string") return;
    if (message.type === "pipkin_git_detect" && typeof message.trusted === "boolean") {
      reply("pipkin_git_detected", id, detect(ctx, root, message.trusted));
    } else if (message.type === "pipkin_git_changes" && (message.mode === "uncommitted" || message.mode === "base") && typeof ignoreWhitespace === "boolean") {
      reply("pipkin_git_changes_result", id, changes(ctx, root, message.mode, ignoreWhitespace, message.known));
    } else if (message.type === "pipkin_git_commits") {
      reply("pipkin_git_commits_result", id, commits(ctx, root, message.known));
    } else if (message.type === "pipkin_git_commit_diff" && typeof message.sha === "string" && typeof ignoreWhitespace === "boolean") {
      reply("pipkin_git_commit_diff_result", id, commitDiff(ctx, root, message.sha, ignoreWhitespace));
    }
  });
  if (!ctx.signal.aborted) {
    const { promise, resolve: hungUp } = Promise.withResolvers<void>();
    ctx.signal.addEventListener("abort", () => hungUp(), { once: true });
    await promise;
  }
  await Promise.allSettled(inFlight);
  return {};
}
