import { execFile, execFileSync } from "node:child_process";
import { chmod, mkdir, readdir, readFile, rm, symlink, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { JsonObject } from "../src/framing.js";
import { type Dirs, type HostRun, makeDirs, removeDirs, startHost } from "./harness.js";

const execFileAsync = promisify(execFile);
const SYSTEM_GIT = execFileSync("sh", ["-c", "command -v git"], { encoding: "utf8" }).trim();
/** Keeps the developer's git config (signing, prefixes, quoting) out of fixtures and the host alike. */
const ISOLATED = { GIT_CONFIG_NOSYSTEM: "1", GIT_CONFIG_GLOBAL: "/dev/null" };
const MIB = 1024 * 1024;
/** The empty file's blob id, which `git add --intent-to-add` writes. */
const EMPTY_BLOB = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";

interface Hunk {
  header: string;
  oldStart: number;
  newStart: number;
  lines: string[];
}

interface DiffFile {
  path: string;
  oldPath?: string;
  change: string;
  additions: number;
  deletions: number;
  kind: string;
  hunks?: Hunk[];
}

const filesOf = (reply: JsonObject | undefined) => reply?.files as DiffFile[];
const byPath = (reply: JsonObject | undefined) => Object.fromEntries(filesOf(reply).map((file) => [file.path, file]));
const pathsOf = (reply: JsonObject | undefined) => filesOf(reply).map((file) => file.path);
/** [lines] lines of 99 characters each. */
const text = (lines: number) => `${"x".repeat(99)}\n`.repeat(lines);

/** [root]'s repo's loose objects and packs. */
async function objectStore(root: string): Promise<string[]> {
  const objects = path.join(root, ".git", "objects");
  const entries: string[] = [];
  for (const name of await readdir(objects)) {
    if (!/^[0-9a-f]{2}$/.test(name)) continue;
    for (const file of await readdir(path.join(objects, name))) entries.push(`${name}/${file}`);
  }
  for (const file of await readdir(path.join(objects, "pack"))) entries.push(`pack/${file}`);
  return entries.toSorted();
}

describe("git mode", () => {
  let dirs: Dirs;
  let workspace: string;
  let tmp: string;
  let run: HostRun;
  let nextId = 0;
  /** The time of the latest fixture commit; each commit is a minute later, so log order doesn't hang on ties. */
  let clock = 1_700_000_000;

  async function git(cwd: string, ...args: string[]): Promise<string> {
    const date = `${clock} +0000`;
    const { stdout } = await execFileAsync("git", args, {
      cwd,
      env: {
        ...process.env,
        ...ISOLATED,
        GIT_AUTHOR_NAME: "Test",
        GIT_AUTHOR_EMAIL: "test@example.com",
        GIT_COMMITTER_NAME: "Test",
        GIT_COMMITTER_EMAIL: "test@example.com",
        GIT_AUTHOR_DATE: date,
        GIT_COMMITTER_DATE: date,
      },
      maxBuffer: 64 * MIB,
    });
    return stdout;
  }

  async function commitAll(cwd: string, message: string): Promise<string> {
    clock += 60;
    await git(cwd, "add", "-A");
    await git(cwd, "commit", "-q", "--allow-empty", "-m", message);
    return (await git(cwd, "rev-parse", "HEAD")).trim();
  }

  async function write(relative: string, content: string | Buffer, root = workspace): Promise<void> {
    await mkdir(path.dirname(path.join(root, relative)), { recursive: true });
    await writeFile(path.join(root, relative), content);
  }

  /** A workspace repo on `main` with one commit holding [files]. */
  async function initRepo(files: Record<string, string> = { "README.md": "hello\n" }, root = workspace): Promise<string> {
    await mkdir(root, { recursive: true });
    await git(root, "init", "-q", "-b", "main");
    for (const [name, content] of Object.entries(files)) await write(name, content, root);
    return commitAll(root, "initial");
  }

  /** Points `$PREFIX/bin/git` at a shell script run before the real git. */
  async function wrapGit(script: string): Promise<void> {
    const wrapper = path.join(dirs.prefix, "bin", "git");
    await rm(wrapper);
    await writeFile(wrapper, `#!/bin/sh\n${script}\nexec ${SYSTEM_GIT} "$@"\n`);
    await chmod(wrapper, 0o755);
  }

  beforeEach(async () => {
    dirs = await makeDirs();
    workspace = path.join(dirs.base, "workspace");
    tmp = path.join(dirs.base, "tmp");
    await mkdir(tmp);
    await mkdir(path.join(dirs.prefix, "bin"));
    await symlink(SYSTEM_GIT, path.join(dirs.prefix, "bin", "git"));
    ({ run } = await startHost("git", dirs, { ...ISOLATED, TMPDIR: tmp }));
    await run.next();
    run.send({ type: "pipkin_request", params: {} });
  });

  afterEach(async () => {
    run.socket.destroy();
    await removeDirs(dirs);
  });

  /** Sends [type] with a fresh id and the workspace root, and returns the reply carrying that id. */
  async function request(type: string, fields: JsonObject): Promise<JsonObject | undefined> {
    const id = ++nextId;
    run.send({ type, id, root: workspace, ...fields });
    for (;;) {
      const message = await run.next();
      if (message === undefined || message.id === id) return message;
    }
  }

  const detect = (trusted: boolean, root = workspace) => request("pipkin_git_detect", { root, trusted });
  const changes = (fields: JsonObject = {}) => request("pipkin_git_changes", { mode: "uncommitted", ignoreWhitespace: false, ...fields });
  const commits = (fields: JsonObject = {}) => request("pipkin_git_commits", fields);
  const commitDiff = (sha: string, ignoreWhitespace = false) => request("pipkin_git_commit_diff", { sha, ignoreWhitespace });
  const committed = () => changes({ mode: "base" });

  it("starts with a ready message", async () => {
    expect(await run.next()).toEqual({ type: "pipkin_git_ready" });
  });

  describe("detect", () => {
    it("finds a repo only where .git sits in the root itself", async () => {
      await mkdir(workspace);
      expect(await detect(true)).toEqual({ type: "pipkin_git_detected", id: nextId, repo: false, git: true });
      await initRepo({ "sub/file.txt": "x\n" }, path.join(workspace, "nested"));
      expect(await detect(true)).toMatchObject({ repo: false });
      expect(await detect(false)).toMatchObject({ repo: false });
      expect(await detect(true, path.join(workspace, "nested"))).toMatchObject({ repo: true, git: true });
      expect(await detect(false, path.join(workspace, "nested"))).toMatchObject({ repo: true, git: true });
      // `.git` only in a parent folder doesn't make the subfolder a repo.
      expect(await detect(true, path.join(workspace, "nested", "sub"))).toMatchObject({ repo: false });
      expect(await detect(false, path.join(workspace, "nested", "sub"))).toMatchObject({ repo: false });
    });

    it("counts a linked worktree's .git file", async () => {
      await initRepo();
      const linked = path.join(dirs.base, "linked");
      await git(workspace, "worktree", "add", "-q", "-b", "side", linked);
      expect(await detect(true, linked)).toMatchObject({ repo: true, git: true });
    });

    it("hides a trusted repo git can't read, but never runs git for an untrusted one", async () => {
      await mkdir(workspace);
      await writeFile(path.join(workspace, ".git"), "gitdir: /nowhere/at/all\n");
      const ran = path.join(dirs.base, "ran");
      await wrapGit(`echo "$@" >> ${ran}`);
      expect(await detect(false)).toMatchObject({ repo: true, git: true });
      await expect(readFile(ran)).rejects.toThrow("ENOENT");
      expect(await detect(true)).toMatchObject({ repo: false, git: true });
      expect(await readFile(ran, "utf8")).toContain("rev-parse");
    });

    it("reports git missing, and refuses requests without it", async () => {
      await initRepo();
      await unlink(path.join(dirs.prefix, "bin", "git"));
      expect(await detect(true)).toMatchObject({ repo: true, git: false });
      expect(await changes()).toEqual({
        type: "pipkin_git_changes_result",
        id: nextId,
        error: "git isn't installed in Termux.",
        code: "no_git",
        errorI18n: { key: "git_missing" },
      });
      expect(await commits()).toMatchObject({ code: "no_git" });
    });
  });

  describe("uncommitted changes", () => {
    it("diffs HEAD against the work tree: staged, unstaged, deleted and untracked, not ignored", async () => {
      await initRepo({ "a.txt": "one\ntwo\nthree\n", "gone.txt": "bye\n", ".gitignore": "*.log\n" });
      await write("a.txt", "ONE\ntwo\nthree\n");
      await git(workspace, "add", "a.txt");
      await write("a.txt", "ONE\ntwo\nTHREE\n");
      await rm(path.join(workspace, "gone.txt"));
      await write("new/b.txt", "fresh\n");
      await write("debug.log", "ignored\n");
      const index = await readFile(path.join(workspace, ".git", "index"));

      const reply = await changes();
      expect(reply).toMatchObject({ type: "pipkin_git_changes_result", branch: "main", baseRef: "main", fingerprint: expect.any(String) });
      expect(filesOf(reply)).toEqual([
        {
          path: "a.txt",
          change: "modified",
          additions: 2,
          deletions: 2,
          kind: "text",
          hunks: [{ header: "@@ -1,3 +1,3 @@", oldStart: 1, newStart: 1, lines: ["-one", "+ONE", " two", "-three", "+THREE"] }],
        },
        {
          path: "gone.txt",
          change: "deleted",
          additions: 0,
          deletions: 1,
          kind: "text",
          hunks: [{ header: "@@ -1 +0,0 @@", oldStart: 1, newStart: 1, lines: ["-bye"] }],
        },
        {
          path: "new/b.txt",
          change: "added",
          additions: 1,
          deletions: 0,
          kind: "text",
          hunks: [{ header: "@@ -0,0 +1 @@", oldStart: 1, newStart: 1, lines: ["+fresh"] }],
        },
      ]);
      expect(await readFile(path.join(workspace, ".git", "index"))).toEqual(index);
      expect(await git(workspace, "status", "--porcelain")).toContain("?? new/");
      expect(await readdir(tmp)).toEqual([]);
    });

    it("never writes the repo's object store, not even the empty blob intent-to-add records", async () => {
      await initRepo({ "a.txt": "a\n", "b.txt": "b\n" });
      await git(workspace, "repack", "-q", "-a", "-d");
      await write("b.txt", "loose\n");
      await git(workspace, "add", "b.txt");
      await write("a.txt", "changed\n");
      await write("new.txt", "n\n");
      const before = await objectStore(workspace);
      expect(before.some((entry) => entry.startsWith("pack/"))).toBe(true);
      expect(before.some((entry) => !entry.startsWith("pack/"))).toBe(true);
      expect(pathsOf(await changes())).toEqual(["a.txt", "b.txt", "new.txt"]);
      expect(await objectStore(workspace)).toEqual(before);
      await expect(git(workspace, "cat-file", "-e", EMPTY_BLOB)).rejects.toThrow("Command failed");
      expect(await readdir(tmp)).toEqual([]);
    });

    it.skipIf(process.getuid?.() === 0)("diffs a repo whose object store is read-only", async () => {
      await initRepo({ "a.txt": "a\n" });
      await write("a.txt", "changed\n");
      await write("new.txt", "n\n");
      const objects = path.join(workspace, ".git", "objects");
      const folders = [objects, ...(await readdir(objects)).map((name) => path.join(objects, name))];
      await Promise.all(folders.map((folder) => chmod(folder, 0o555)));
      try {
        expect(filesOf(await changes()).map((file) => [file.path, file.change, file.hunks?.[0]?.lines])).toEqual([
          ["a.txt", "modified", ["-a", "+changed"]],
          ["new.txt", "added", ["+n"]],
        ]);
      } finally {
        await Promise.all(folders.map((folder) => chmod(folder, 0o755)));
      }
      expect(await readdir(tmp)).toEqual([]);
    });

    it("shows a rename under its new path with the old one beside it", async () => {
      await initRepo({ "old name.txt": "line 1\nline 2\nline 3\nline 4\n" });
      await rm(path.join(workspace, "old name.txt"));
      await write("new name.txt", "line 1\nline 2\nline 3\nline 4 changed\n");
      expect(filesOf(await changes())).toEqual([
        expect.objectContaining({ path: "new name.txt", oldPath: "old name.txt", change: "renamed", additions: 1, deletions: 1, kind: "text" }),
      ]);
    });

    it("keeps odd file names, CRLF lines, binaries, empty files, mode changes and symlinks apart", async () => {
      await initRepo({ "run.sh": "echo hi\n", "typed.txt": "plain\n" });
      const odd = 'dir with space/naïve "quoted" ✓.txt';
      await write(odd, "odd\n");
      await write("crlf.txt", "first\r\nsecond\r\n");
      await write("image.bin", Buffer.from([0x89, 0x50, 0x00, 0x01, 0x02]));
      await write("empty.txt", "");
      await chmod(path.join(workspace, "run.sh"), 0o755);
      await symlink("run.sh", path.join(workspace, "link"));
      await rm(path.join(workspace, "typed.txt"));
      await symlink("run.sh", path.join(workspace, "typed.txt"));

      const files = byPath(await changes());
      expect(Object.keys(files)).toEqual(["crlf.txt", odd, "empty.txt", "image.bin", "link", "run.sh", "typed.txt"]);
      expect(files[odd]).toMatchObject({ change: "added", kind: "text", hunks: [{ lines: ["+odd"] }] });
      expect(files["crlf.txt"]).toMatchObject({ additions: 2, hunks: [{ lines: ["+first\r", "+second\r"] }] });
      expect(files["image.bin"]).toEqual({ path: "image.bin", change: "added", additions: 0, deletions: 0, kind: "binary" });
      expect(files["empty.txt"]).toEqual({ path: "empty.txt", change: "added", additions: 0, deletions: 0, kind: "text", hunks: [] });
      expect(files["run.sh"]).toEqual({ path: "run.sh", change: "modified", additions: 0, deletions: 0, kind: "text", hunks: [] });
      expect(files.link).toMatchObject({ change: "added", kind: "text", hunks: [{ lines: ["+run.sh", "\\ No newline at end of file"] }] });
      // git patches a file turned symlink as a deletion then a creation; both land on the one path.
      expect(files["typed.txt"]).toMatchObject({
        change: "modified",
        additions: 1,
        deletions: 1,
        hunks: [{ lines: ["-plain"] }, { lines: ["+run.sh", "\\ No newline at end of file"] }],
      });
    });

    it("diffs an unborn branch against the empty tree", async () => {
      await mkdir(workspace);
      await git(workspace, "init", "-q", "-b", "trunk");
      await write("staged.txt", "s\n");
      await git(workspace, "add", "staged.txt");
      await write("loose.txt", "l\n");
      const reply = await changes();
      expect(reply).toMatchObject({ branch: "trunk", baseRef: null });
      expect(filesOf(reply).map((file) => [file.path, file.change])).toEqual([
        ["loose.txt", "added"],
        ["staged.txt", "added"],
      ]);
      expect(await readdir(tmp)).toEqual([]);
    });

    it("works without an index file and with a detached HEAD", async () => {
      const first = await initRepo();
      await commitAll(workspace, "second");
      await git(workspace, "checkout", "-q", "--detach", first);
      await rm(path.join(workspace, ".git", "index"));
      await write("README.md", "changed\n");
      const reply = await changes();
      expect(reply).toMatchObject({ branch: null, baseRef: "main" });
      // With no index every file looks untracked, so README.md reads as modified against HEAD.
      expect(filesOf(reply)).toEqual([expect.objectContaining({ path: "README.md", change: "modified" })]);
      await expect(readFile(path.join(workspace, ".git", "index"))).rejects.toThrow("ENOENT");
    });

    it("hides whitespace-only edits and whitespace within edits when asked", async () => {
      await initRepo({ "spaces.txt": "a b\n", "mixed.txt": "x\ny\n" });
      await write("spaces.txt", "a  b\n");
      await write("mixed.txt", "x \nY\n");
      expect(filesOf(await changes()).map((file) => [file.path, file.additions])).toEqual([
        ["mixed.txt", 2],
        ["spaces.txt", 1],
      ]);
      expect(filesOf(await changes({ ignoreWhitespace: true }))).toEqual([
        // -w keeps the new side's spacing on context lines.
        expect.objectContaining({ path: "mixed.txt", additions: 1, deletions: 1, hunks: [expect.objectContaining({ lines: [" x ", "-y", "+Y"] })] }),
      ]);
    });

    it("answers with the fingerprint alone while nothing changed", async () => {
      await initRepo();
      await write("README.md", "edited\n");
      const first = await changes();
      const fingerprint = first?.fingerprint as string;
      expect(await changes({ known: fingerprint })).toEqual({ type: "pipkin_git_changes_result", id: nextId, fingerprint, unchanged: true });
      expect(await changes({ known: fingerprint, ignoreWhitespace: true })).toEqual({ type: "pipkin_git_changes_result", id: nextId, fingerprint, unchanged: true });
      await write("README.md", "edited again\n");
      const changed = await changes({ known: fingerprint });
      expect(changed?.fingerprint).not.toBe(fingerprint);
      expect(changed).not.toHaveProperty("unchanged");
      expect(filesOf(changed)[0]?.hunks?.[0]?.lines).toEqual(["-hello", "+edited again"]);
    });

    it("refuses a folder git doesn't read as a repo", async () => {
      await mkdir(workspace);
      expect(await changes()).toMatchObject({ code: "not_repo", error: expect.stringContaining("not a git repository") });
      const nowhere = path.join(dirs.base, "nowhere");
      expect(await request("pipkin_git_changes", { root: nowhere, mode: "uncommitted", ignoreWhitespace: false })).toMatchObject({
        code: "not_repo",
        errorI18n: { key: "folder_not_found", args: { path: nowhere } },
      });
    });
  });

  describe("limits", () => {
    it("marks a file over 1 MiB too large and still shows the rest", async () => {
      await initRepo();
      await write("big.txt", text(11_000));
      await write("small.txt", "small\n");
      const files = byPath(await changes());
      expect(files["big.txt"]).toEqual({ path: "big.txt", change: "added", additions: 11_000, deletions: 0, kind: "too_large" });
      expect(files["small.txt"]).toMatchObject({ kind: "text", hunks: [{ lines: ["+small"] }] });
    });

    it("marks a file too large once it can't fit in what's left of 2 MiB, while later smaller files still fit", async () => {
      await initRepo();
      // Each 7000-line file's diff is about 0.67 MiB; a 3000-line one about 0.29 MiB.
      for (const name of ["a.txt", "b.txt", "c.txt", "e.txt"]) await write(name, text(7000));
      await write("d.txt", text(3000));
      await write("f.txt", "small\n");
      const files = byPath(await changes());
      expect(Object.values(files).map((file) => [file.path, file.kind, file.additions])).toEqual([
        ["a.txt", "text", 7000],
        ["b.txt", "text", 7000],
        ["c.txt", "too_large", 7000],
        ["d.txt", "text", 3000],
        ["e.txt", "too_large", 7000],
        ["f.txt", "text", 1],
      ]);
      expect(files["a.txt"]?.hunks?.[0]?.lines).toHaveLength(7000);
      expect(files["f.txt"]?.hunks?.[0]?.lines).toEqual(["+small"]);
    });

    it("calls the whole diff too large when its file list can't fit in a reply", async () => {
      await initRepo();
      // Long paths make thousands of files enough to pass the reply limit.
      const folder = Array.from({ length: 4 }, (_, i) => `${String(i)}${"d".repeat(180)}`).join("/");
      await mkdir(path.join(workspace, folder), { recursive: true });
      const name = "f".repeat(150);
      await Promise.all(Array.from({ length: 12_000 }, (_, i) => writeFile(path.join(workspace, folder, `${name}${String(i)}`), "")));
      const reply = await changes();
      expect(reply).toMatchObject({ branch: "main", tooLarge: true, files: [] });
      expect(await readdir(tmp)).toEqual([]);
    }, 60_000);
  });

  describe("committed changes", () => {
    let upstream: string;

    /** An upstream repo on `main`, cloned as the workspace so `origin/HEAD` names `origin/main`. */
    async function cloneUpstream(): Promise<void> {
      upstream = path.join(dirs.base, "upstream");
      await initRepo({ "base.txt": "base\n" }, upstream);
      await git(dirs.base, "clone", "-q", upstream, workspace);
    }

    it("diffs the branch against its merge-base with origin/HEAD's branch, leaving out base-only commits", async () => {
      await cloneUpstream();
      await git(workspace, "checkout", "-q", "-b", "feature");
      await write("feature.txt", "f\n");
      await commitAll(workspace, "feature work");
      await write("uncommitted.txt", "u\n");
      await write("base.txt", "upstream moved on\n", upstream);
      await commitAll(upstream, "upstream work");
      await git(workspace, "fetch", "-q");
      const reply = await committed();
      expect(reply).toMatchObject({ branch: "feature", baseRef: "main" });
      expect(filesOf(reply)).toEqual([
        { path: "feature.txt", change: "added", additions: 1, deletions: 0, kind: "text", hunks: [{ header: "@@ -0,0 +1 @@", oldStart: 1, newStart: 1, lines: ["+f"] }] },
      ]);
    });

    it("names origin's branch when there's no local one", async () => {
      await cloneUpstream();
      await git(workspace, "checkout", "-q", "-b", "feature");
      await git(workspace, "branch", "-q", "-D", "main");
      await write("feature.txt", "f\n");
      await commitAll(workspace, "feature work");
      const reply = await committed();
      expect(reply).toMatchObject({ baseRef: "origin/main" });
      expect(pathsOf(reply)).toEqual(["feature.txt"]);
    });

    it("falls back to local main, then master, and shows nothing without a base", async () => {
      await initRepo({ "base.txt": "base\n" });
      await git(workspace, "checkout", "-q", "-b", "feature");
      await write("feature.txt", "f\n");
      await commitAll(workspace, "feature work");
      expect(await committed()).toMatchObject({ baseRef: "main", files: [expect.objectContaining({ path: "feature.txt" })] });
      await git(workspace, "branch", "-q", "-m", "main", "master");
      expect(await committed()).toMatchObject({ baseRef: "master", files: [expect.objectContaining({ path: "feature.txt" })] });
      await git(workspace, "branch", "-q", "-m", "master", "trunk");
      expect(await committed()).toMatchObject({ branch: "feature", baseRef: null, files: [] });
      expect(await commits()).toMatchObject({ baseRef: null, commits: [] });
    });

    it("diffs against the base branch itself when the histories are unrelated, like Paseo", async () => {
      await initRepo({ "base.txt": "base\n" });
      await git(workspace, "checkout", "-q", "--orphan", "feature");
      await git(workspace, "rm", "-q", "-r", "-f", ".");
      await write("feature.txt", "f\n");
      await commitAll(workspace, "unrelated");
      const reply = await committed();
      expect(reply).toMatchObject({ branch: "feature", baseRef: "main" });
      expect(filesOf(reply).map((file) => [file.path, file.change])).toEqual([
        ["base.txt", "deleted"],
        ["feature.txt", "added"],
      ]);
    });

    it("names the branch a rebase is rewriting, and lists its commits so far", async () => {
      await initRepo({ "a.txt": "base\n" });
      await git(workspace, "checkout", "-q", "-b", "feature");
      await write("one.txt", "1\n");
      await commitAll(workspace, "feature one");
      await write("a.txt", "feature\n");
      await commitAll(workspace, "feature two");
      await git(workspace, "checkout", "-q", "main");
      await write("a.txt", "main\n");
      await commitAll(workspace, "main moved");
      await git(workspace, "checkout", "-q", "feature");
      // Both backends: `rebase-merge` (the default) and `rebase-apply`.
      for (const backend of ["--merge", "--apply"]) {
        await expect(git(workspace, "rebase", "-q", backend, "main")).rejects.toThrow("Command failed");
        await expect(git(workspace, "symbolic-ref", "-q", "HEAD")).rejects.toThrow("Command failed");
        expect(await changes()).toMatchObject({ branch: "feature", baseRef: "main" });
        expect(await committed()).toMatchObject({ branch: "feature" });
        const reply = await commits();
        expect(reply).toMatchObject({ baseRef: "main", total: 1 });
        expect((reply?.commits as { subject: string }[] | undefined)?.map((commit) => commit.subject)).toEqual(["feature one"]);
        await git(workspace, "rebase", "--abort");
      }
    });

    it("diffs against origin's branch even when the local one is ahead, like Paseo", async () => {
      await cloneUpstream();
      await write("unpushed.txt", "u\n");
      await commitAll(workspace, "unpushed on main");
      await git(workspace, "checkout", "-q", "-b", "feature");
      await write("feature.txt", "f\n");
      await commitAll(workspace, "feature work");
      expect(pathsOf(await committed())).toEqual(["feature.txt", "unpushed.txt"]);
    });

    it("lists the commits ahead of origin's branch, on the base branch itself too", async () => {
      await cloneUpstream();
      const unpushed = await commitAll(workspace, "unpushed on main");
      expect(await commits()).toMatchObject({ baseRef: "origin/main", total: 1, commits: [{ sha: unpushed, subject: "unpushed on main", authorTime: clock }] });

      await git(workspace, "checkout", "-q", "-b", "feature");
      await commitAll(workspace, "feature work");
      const feature = await commits();
      expect(feature).toMatchObject({ baseRef: "origin/main", total: 2 });
      expect((feature?.commits as { subject: string }[] | undefined)?.map((commit) => commit.subject)).toEqual(["feature work", "unpushed on main"]);
    });

    it("lists the branch's commits newest first, none ahead on a base branch without origin, detached or unborn", async () => {
      await initRepo();
      await git(workspace, "checkout", "-q", "-b", "feature");
      const first = await commitAll(workspace, "first: with \"quotes\" and ünïcode");
      const firstTime = clock;
      const second = await commitAll(workspace, "second");
      const reply = await commits();
      expect(reply).toEqual({
        type: "pipkin_git_commits_result",
        id: nextId,
        fingerprint: expect.any(String),
        baseRef: "main",
        total: 2,
        commits: [
          { sha: second, subject: "second", authorTime: clock },
          { sha: first, subject: 'first: with "quotes" and ünïcode', authorTime: firstTime },
        ],
      });
      expect(await commits({ known: reply?.fingerprint })).toEqual({ type: "pipkin_git_commits_result", id: nextId, fingerprint: reply?.fingerprint, unchanged: true });

      await git(workspace, "checkout", "-q", "--detach");
      expect(await commits()).toMatchObject({ baseRef: null, total: 0, commits: [] });
      await git(workspace, "checkout", "-q", "main");
      expect(await commits()).toMatchObject({ baseRef: "main", total: 0, commits: [] });
      await git(workspace, "checkout", "-q", "--orphan", "fresh");
      expect(await commits()).toMatchObject({ baseRef: null, total: 0, commits: [] });
    });

    it("lists the newest 500 commits and counts them all", async () => {
      const base = await initRepo();
      // fast-import writes the 501 commits in one go.
      const stream = Array.from({ length: 501 }, (_, i) => {
        const message = `commit ${String(i + 1)}`;
        const parent = i === 0 ? `from ${base}\n` : "";
        return `commit refs/heads/feature\ncommitter Test <test@example.com> ${String(clock + i + 1)} +0000\ndata ${String(message.length)}\n${message}\n${parent}\n`;
      }).join("");
      execFileSync("git", ["fast-import", "--quiet"], { cwd: workspace, input: stream, env: { ...process.env, ...ISOLATED } });
      await git(workspace, "checkout", "-q", "feature");
      const reply = await commits();
      const listed = reply?.commits as { subject: string }[];
      expect(reply).toMatchObject({ baseRef: "main", total: 501 });
      expect(listed).toHaveLength(500);
      expect(listed[0]?.subject).toBe("commit 501");
      expect(listed[499]?.subject).toBe("commit 2");
    });
  });

  describe("commit diffs", () => {
    it("diffs a commit against its parent, and a root commit against nothing", async () => {
      const root = await initRepo({ "a.txt": "one\n" });
      await write("a.txt", "one  \ntwo\n");
      const second = await commitAll(workspace, "second");
      expect(await commitDiff(second)).toEqual({
        type: "pipkin_git_commit_diff_result",
        id: nextId,
        files: [
          {
            path: "a.txt",
            change: "modified",
            additions: 2,
            deletions: 1,
            kind: "text",
            hunks: [{ header: "@@ -1 +1,2 @@", oldStart: 1, newStart: 1, lines: ["-one", "+one  ", "+two"] }],
          },
        ],
      });
      expect(filesOf(await commitDiff(second.slice(0, 7), true))[0]).toMatchObject({ additions: 1, deletions: 0 });
      expect(filesOf(await commitDiff(root))).toEqual([expect.objectContaining({ path: "a.txt", change: "added", hunks: [expect.objectContaining({ lines: ["+one"] })] })]);
    });

    it("diffs a merge against its first parent", async () => {
      await initRepo();
      await git(workspace, "checkout", "-q", "-b", "side");
      await write("side.txt", "s\n");
      await commitAll(workspace, "side work");
      await git(workspace, "checkout", "-q", "main");
      await write("main.txt", "m\n");
      await commitAll(workspace, "main work");
      await git(workspace, "merge", "-q", "--no-ff", "-m", "merge side", "side");
      const merge = (await git(workspace, "rev-parse", "HEAD")).trim();
      expect(filesOf(await commitDiff(merge)).map((file) => file.path)).toEqual(["side.txt"]);
    });

    it("reports commits git can't find", async () => {
      await initRepo();
      expect(await commitDiff("0123456789abcdef0123456789abcdef01234567")).toEqual({
        type: "pipkin_git_commit_diff_result",
        id: nextId,
        error: "Commit 0123456789abcdef0123456789abcdef01234567 not found.",
        code: "commit_not_found",
      });
      expect(await commitDiff("--output=/tmp/x")).toMatchObject({ code: "commit_not_found" });
      expect(await commitDiff("HEAD")).toMatchObject({ code: "commit_not_found" });
    });
  });

  it("removes the temporary index when the app hangs up mid-request", async () => {
    await initRepo();
    await write("new.txt", "n\n");
    // git reports the temporary index through a FIFO, then hangs while it is in use.
    const fifo = path.join(dirs.base, "started");
    await execFileAsync("mkfifo", [fifo]);
    await wrapGit(`[ -n "$GIT_INDEX_FILE" ] && { echo "$GIT_INDEX_FILE" > ${fifo}; exec sleep 30; }`);
    run.send({ type: "pipkin_git_changes", id: ++nextId, root: workspace, mode: "uncommitted", ignoreWhitespace: false });
    const tempIndex = (await readFile(fifo, "utf8")).trim();
    expect(path.dirname(path.dirname(tempIndex))).toBe(tmp);
    expect(await readdir(tmp)).toHaveLength(1);
    run.socket.destroy();
    await run.exited;
    expect(await readdir(tmp)).toEqual([]);
    expect(await git(workspace, "status", "--porcelain")).toBe("?? new.txt\n");
  });

  describe("git that runs past the time limit", () => {
    beforeEach(async () => {
      run.socket.destroy();
      await run.exited;
      ({ run } = await startHost("git", dirs, { ...ISOLATED, TMPDIR: tmp, PIPKIN_GIT_TIMEOUT_MS: "500" }));
      await run.next();
      run.send({ type: "pipkin_request", params: {} });
    });

    // The sleep is a separate process git's wrapper waits on, like a textconv filter, holding git's output open.
    it("reports a diff git can't produce in time as too large and drops the temporary index", async () => {
      await initRepo();
      await write("README.md", "changed\n");
      await wrapGit(`case " $* " in *" --numstat "*) sleep 30; exit 1;; esac`);
      expect(await changes()).toMatchObject({ tooLarge: true, files: [] });
      expect(await readdir(tmp)).toEqual([]);
    });

    it("still fails a slow command that isn't a diff", async () => {
      await initRepo();
      await git(workspace, "checkout", "-q", "-b", "feature");
      await write("feature.txt", "f\n");
      await commitAll(workspace, "feature");
      await wrapGit(`case " $* " in *" log "*) sleep 30; exit 1;; esac`);
      const reply = await commits();
      expect(reply).toMatchObject({ code: "failed" });
      expect(reply).not.toHaveProperty("commits");
    });
  });

  it("ignores malformed requests", async () => {
    await initRepo();
    run.send({ type: "pipkin_git_changes", id: "1", root: workspace, mode: "uncommitted", ignoreWhitespace: false });
    run.send({ type: "pipkin_git_changes", id: 1000, root: workspace, mode: "staged", ignoreWhitespace: false });
    run.send({ type: "pipkin_git_detect", id: 1001, root: workspace });
    run.send({ type: "pipkin_git_commit_diff", id: 1002, root: workspace, sha: "abc" });
    run.send({ type: "pipkin_git_detect", id: 1003, root: workspace, trusted: false });
    const seen: unknown[] = [];
    for (;;) {
      const message = await run.next();
      if (message === undefined || message.id === 1003) break;
      seen.push(message.type);
    }
    expect(seen).toEqual(["pipkin_git_ready"]);
  });
});
