import { execFile, execFileSync, spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { once } from "node:events";
import { chmod, mkdir, readdir, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { isObject, type JsonObject } from "../src/framing.js";
import { remoteShellCommand } from "../src/modes/terminal.js";
import { findHost, type Host } from "../src/remote/hosts.js";
import { remotePaths } from "../src/remote/paths.js";
import { quote } from "../src/remote/script.js";
import { isConnected, type Remote, runScript, sessionFailure, sessionScript, shBinary } from "../src/remote/ssh.js";
import { type Dirs, type HostRun, runOp, startHost } from "./harness.js";
import { eventually, signalEach } from "./remote-harness.js";
import { freePort, linkSshTools, shortDirs, sshdUnavailable, startSshd, type TestSshd } from "./sshd.js";

const exec = promisify(execFile);
const unavailable = await sshdUnavailable();
const user = os.userInfo().username;
const SYSTEM_GIT = execFileSync("sh", ["-c", "command -v git"], { encoding: "utf8" }).trim();
/** Keeps the developer's git config out of fixtures and of git in the host, here and on the remote alike. */
const ISOLATED = { GIT_CONFIG_NOSYSTEM: "1", GIT_CONFIG_GLOBAL: "/dev/null" };
const MIB = 1024 * 1024;
const FAKE_SCRIPT = path.join(import.meta.dirname, "fixtures/fake-script.py");

/** The string at [keys] inside [value], failing the test when it isn't one. */
function stringAt(value: unknown, ...keys: string[]): string {
  const found = keys.reduce<unknown>((inner, key) => (isObject(inner) ? inner[key] : undefined), value);
  if (typeof found !== "string") throw new Error(`No string at ${keys.join(".")} in ${JSON.stringify(value)}`);
  return found;
}

/** Listing [reply] without its entries' times, which a remote gives in whole seconds. */
function timeless(reply: JsonObject): JsonObject {
  const entries = (reply.entries as JsonObject[]).map((entry) => Object.fromEntries(Object.entries(entry).filter(([key]) => key !== "modified")));
  return { ...reply, entries };
}

/** A long-lived mode's host, answering requests by id. */
class ModeHost {
  private nextId = 0;

  private constructor(readonly run: HostRun) {}

  static async start(mode: string, dirs: Dirs, params: JsonObject = {}, env: Record<string, string> = {}): Promise<ModeHost> {
    const { run } = await startHost(mode, dirs, env);
    await run.next();
    run.send({ type: "pipkin_request", params });
    return new ModeHost(run);
  }

  /** Sends [type] with a fresh id and [fields], and returns the reply carrying that id, without the id. */
  async request(type: string, fields: JsonObject): Promise<JsonObject> {
    const id = ++this.nextId;
    this.run.send({ type, id, ...fields });
    for (;;) {
      const message = await this.run.next();
      if (message === undefined) throw new Error(`The host closed before answering ${type}`);
      if (message.id === id) {
        const { id: _, type: __, ...reply } = message;
        return reply;
      }
    }
  }

  close(): void {
    this.run.socket.destroy();
  }
}

describe.skipIf(unavailable !== null)(`remote workspaces against a throwaway sshd${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let dirs: Dirs;
  /** The remote user's home: the sshd sets `HOME` to it, so nothing reads the developer's own. */
  let remoteHome: string;
  /** The remote's `TMPDIR`. */
  let remoteTmp: string;
  let sshd: TestSshd;
  let noGit: TestSshd;
  let hosts: Record<"main" | "noGit" | "down", string>;
  const locked: string[] = [];

  async function saveHost(name: string, port: number): Promise<string> {
    const host = { name, auth: "key", address: `${user}@127.0.0.1`, port };
    return stringAt(await runOp("remote_host_save", dirs, { host }), "data", "host", "id");
  }

  /** The stand-in for remote folder [remotePath] on [hostId]. */
  async function standIn(hostId: string, remotePath: string): Promise<string> {
    return stringAt(await runOp("remote_workspace", dirs, { hostId, path: remotePath }), "data", "cwd");
  }

  /** A folder nobody but root can open, until the tests end. */
  async function lock(folder: string): Promise<void> {
    await mkdir(folder, { recursive: true });
    await chmod(folder, 0o000);
    locked.push(folder);
  }

  beforeAll(async () => {
    dirs = await shortDirs();
    await linkSshTools(dirs.prefix);
    await symlink(SYSTEM_GIT, path.join(dirs.prefix, "bin", "git"));
    remoteHome = path.join(dirs.base, "rhome");
    remoteTmp = path.join(dirs.base, "rtmp");
    await mkdir(remoteTmp);
    await mkdir(remoteHome);
    // A login shell without them would ask zsh's new-user questions in the terminal.
    for (const profile of [".zshrc", ".bashrc", ".profile"]) await writeFile(path.join(remoteHome, profile), "");
    const key = stringAt(await runOp("remote_key", dirs, {}), "data", "publicKey");
    const environment = `HOME=${remoteHome} TMPDIR=${remoteTmp} GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null`;
    sshd = await startSshd(path.join(dirs.base, "sshd"), {
      authorizedKeys: key,
      forceCommand: `exec /usr/bin/env ${environment} PATH=${path.dirname(SYSTEM_GIT)}:/usr/bin:/bin /bin/sh -c "$SSH_ORIGINAL_COMMAND"`,
    });
    // Everything the command wrapper and its watchdog need, but no git.
    const tools = path.join(dirs.base, "tools");
    await mkdir(tools);
    for (const tool of ["bash", "base64", "cat"]) {
      await symlink(execFileSync("sh", ["-c", `command -v ${tool}`], { encoding: "utf8" }).trim(), path.join(tools, tool));
    }
    noGit = await startSshd(path.join(dirs.base, "nogit"), {
      authorizedKeys: key,
      forceCommand: `exec /usr/bin/env ${environment} PATH=${tools} /bin/sh -c "$SSH_ORIGINAL_COMMAND"`,
    });
    hosts = { main: await saveHost("main", sshd.port), noGit: await saveHost("nogit", noGit.port), down: await saveHost("down", await freePort()) };
    const knownHosts = path.join(dirs.base, "pipkin", "ssh", "known_hosts");
    await writeFile(knownHosts, `[127.0.0.1]:${sshd.port} ${sshd.hostKey}\n[127.0.0.1]:${noGit.port} ${noGit.hostKey}\n`);
  });

  afterAll(async () => {
    for (const id of Object.values(hosts)) await runOp("remote_disconnect", dirs, { id });
    await sshd.stop();
    await noGit.stop();
    for (const folder of locked) await chmod(folder, 0o755);
    await rm(dirs.base, { recursive: true, force: true });
  });

  describe("browse", () => {
    let browser: ModeHost;

    beforeAll(async () => {
      for (const folder of ["api", "src/api-server", "src/it's $x [a]*", "src/it's $x ab", "node_modules/api", ".hidden/api"]) {
        await mkdir(path.join(remoteHome, folder), { recursive: true });
      }
      await writeFile(path.join(remoteHome, "api.txt"), "not a folder");
      await symlink("src", path.join(remoteHome, "link-to-src"));
      await lock(path.join(remoteHome, "locked"));
      browser = await ModeHost.start("browse", dirs, { hostId: hosts.main });
    });

    afterAll(() => browser.close());

    const list = (target: string) => browser.request("pipkin_list", { path: target });
    const search = async (query: string) => (await browser.request("pipkin_search", { query })).paths;

    it("offers the remote home and root", async () => {
      expect(await browser.run.next()).toEqual({
        type: "pipkin_places",
        places: [
          { kind: "remote_home", path: remoteHome },
          { kind: "remote_root", path: "/" },
        ],
      });
    });

    it("saves the home of a host reached here before any connect", async () => {
      const saved = (await runOp("remote_hosts", dirs, {})).data as { hosts: Host[] };
      expect(saved.hosts.find((host) => host.id === hosts.main)?.home).toBe(remoteHome);
      expect(saved.hosts.find((host) => host.id === hosts.noGit)?.home).toBeNull();
    });

    it("lists the remote home's folders, links to folders included, dot folders last", async () => {
      expect(await list("~")).toEqual({
        path: remoteHome,
        parent: dirs.base,
        dirs: ["api", "link-to-src", "locked", "node_modules", "src", ".hidden"],
      });
      expect(await list(`${remoteHome}/src/../src`)).toEqual({ path: `${remoteHome}/src`, parent: remoteHome, dirs: ["api-server", "it's $x [a]*", "it's $x ab"] });
    });

    it("lists the root, which has no parent", async () => {
      const root = await list("/");
      expect(root).toMatchObject({ path: "/", parent: null });
      expect(root.dirs).toContain("usr");
      expect(await list("/usr")).toMatchObject({ path: "/usr", parent: "/" });
    });

    it("says why a folder can't be listed, and still offers Up", async () => {
      expect(await list("~/locked")).toEqual({
        path: `${remoteHome}/locked`,
        parent: remoteHome,
        dirs: [],
        error: "main doesn't let you open this folder.",
        errorI18n: { key: "remote_browse_no_access", args: { host: "main" } },
      });
      expect(await list("~/missing")).toMatchObject({ path: `${remoteHome}/missing`, parent: remoteHome, errorI18n: { key: "browse_folder_missing" } });
      expect(await list("~/api.txt")).toMatchObject({ errorI18n: { key: "not_a_folder" } });
      expect(await list("relative")).toMatchObject({ errorI18n: { key: "browse_path_not_absolute" } });
    });

    it("searches the remote home, best match first, without hidden or ignored folders", async () => {
      expect(await search("api")).toEqual([`${remoteHome}/api`, `${remoteHome}/src/api-server`]);
      expect(await search("  ")).toEqual([]);
    });

    it("searches for a query literally, quotes, $ and glob characters included", async () => {
      expect(await search("it's $x [a]*")).toEqual([`${remoteHome}/src/it's $x [a]*`]);
    });

    it("searches under a typed path's folder", async () => {
      expect(await search("~/src/ap")).toEqual([`${remoteHome}/src/api-server`]);
      expect(await search(`${remoteHome}/src/it`)).toEqual([`${remoteHome}/src/it's $x [a]*`, `${remoteHome}/src/it's $x ab`]);
      expect(await search("~/")).toEqual(expect.arrayContaining([`${remoteHome}/api`, `${remoteHome}/src`]));
    });

    it("says why a host can't be reached, with no places", async () => {
      const down = await ModeHost.start("browse", dirs, { hostId: hosts.down });
      try {
        const places = await down.run.next();
        expect(places).toMatchObject({ type: "pipkin_places", places: [], error: { code: "remote_unreachable", i18n: { key: "remote_unreachable" } } });
        expect(await down.request("pipkin_list", { path: "~" })).toMatchObject({ dirs: [], errorCode: "remote_unreachable" });
      } finally {
        down.close();
      }
      const gone = await ModeHost.start("browse", dirs, { hostId: "0123456789ab" });
      try {
        expect(await gone.run.next()).toMatchObject({ places: [], error: { code: "remote_host_unknown" } });
      } finally {
        gone.close();
      }
    });
  });

  describe("files", () => {
    let files: ModeHost;
    let folder: string;
    let root: string;

    beforeAll(async () => {
      folder = path.join(dirs.base, "files");
      const outside = path.join(dirs.base, "outside");
      await mkdir(path.join(folder, "src"), { recursive: true });
      await mkdir(outside);
      await writeFile(path.join(outside, "secret.txt"), "secret");
      await writeFile(path.join(folder, "src", "main.ts"), "hello");
      await writeFile(path.join(folder, "a b's $x.txt"), "text\n");
      await writeFile(path.join(folder, "pic.png"), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 1, 2]));
      await writeFile(path.join(folder, "bin.dat"), Buffer.from([0, 1, 2, 3]));
      await writeFile(path.join(folder, "big.txt"), "x".repeat(2 * MIB + 1));
      await writeFile(path.join(folder, "bigbin.dat"), Buffer.alloc(2 * MIB + 1));
      await writeFile(path.join(folder, "big.png"), Buffer.alloc(8 * MIB + 1));
      await symlink("src", path.join(folder, "inside"));
      await symlink(outside, path.join(folder, "outside"));
      await symlink(path.join(outside, "secret.txt"), path.join(folder, "outside.txt"));
      await symlink("nowhere", path.join(folder, "dangling"));
      // A sibling whose name is the workspace's and a newline, which `$(…)` would cut back to the workspace's own.
      const lookalike = `${folder}\n`;
      await mkdir(lookalike);
      await writeFile(path.join(lookalike, "secret.txt"), "secret");
      await symlink(lookalike, path.join(folder, "lookalike"));
      await lock(path.join(folder, "locked"));
      root = await standIn(hosts.main, folder);
      files = await ModeHost.start("files", dirs);
    });

    afterAll(() => files.close());

    const request = (type: string, at: string, target: string, fields: JsonObject = {}) => files.request(type, { root: at, path: target, ...fields });

    it("lists the remote folder as the local one, leaving out dangling links and links out of it", async () => {
      const remote = await request("pipkin_files_list", root, ".");
      expect(timeless(remote)).toEqual(timeless(await request("pipkin_files_list", folder, ".")));
      expect((remote.entries as JsonObject[]).map((entry) => entry.name)).toEqual([
        "inside",
        "locked",
        "src",
        "a b's $x.txt",
        "big.png",
        "big.txt",
        "bigbin.dat",
        "bin.dat",
        "pic.png",
      ]);
      expect(timeless(await request("pipkin_files_list", root, "inside"))).toEqual({ path: "inside", entries: [{ name: "main.ts", path: "inside/main.ts", kind: "file", size: 5 }] });
    });

    it("reads files as the local mode does: text, image, binary and too large", async () => {
      for (const file of ["a b's $x.txt", "pic.png", "bin.dat", "big.txt", "bigbin.dat", "big.png", "inside/main.ts", "src", "dangling", "missing"]) {
        expect([file, await request("pipkin_files_read", root, file)]).toEqual([file, await request("pipkin_files_read", folder, file)]);
      }
      expect(await request("pipkin_files_read", root, "big.txt")).toEqual({ path: "big.txt", size: 2 * MIB + 1, kind: "too_large" });
      expect(await request("pipkin_files_read", root, "bigbin.dat")).toEqual({ path: "bigbin.dat", size: 2 * MIB + 1, kind: "binary" });
    });

    it("refuses paths out of the workspace, by .. or through a link", async () => {
      for (const target of ["..", "../outside/secret.txt", "outside.txt", "outside", "/etc", "lookalike", "lookalike/secret.txt"]) {
        expect(await request("pipkin_files_read", root, target)).toMatchObject({ errorI18n: { key: "files_outside_workspace" } });
      }
      for (const target of ["outside", "lookalike"]) {
        expect(await request("pipkin_files_list", root, target)).toMatchObject({ entries: [], errorI18n: { key: "files_outside_workspace" } });
      }
    });

    it("says when the remote refuses a folder, or the workspace is gone", async () => {
      expect(await request("pipkin_files_list", root, "locked")).toEqual({
        path: "locked",
        entries: [],
        error: "main doesn't let you open this.",
        errorI18n: { key: "remote_files_no_access", args: { host: "main" } },
      });
      const gone = await standIn(hosts.main, path.join(dirs.base, "no-such-folder"));
      expect(await request("pipkin_files_list", gone, ".")).toMatchObject({ entries: [], errorI18n: { key: "folder_not_found" } });
    });

    it("makes folders on the remote", async () => {
      expect(await request("pipkin_files_mkdir", root, "src", { name: " it's new " })).toEqual({ path: "src/it's new" });
      expect(await readdir(path.join(folder, "src"))).toContain("it's new");
      expect(await request("pipkin_files_mkdir", root, "src", { name: "it's new" })).toMatchObject({ errorI18n: { key: "files_name_taken", args: { name: "it's new" } } });
      expect(await request("pipkin_files_mkdir", root, "inside", { name: "main.ts" })).toMatchObject({ errorI18n: { key: "files_name_taken" } });
      expect(await request("pipkin_files_mkdir", root, "pic.png", { name: "x" })).toMatchObject({ errorI18n: { key: "not_a_folder" } });
      expect(await request("pipkin_files_mkdir", root, "outside", { name: "x" })).toMatchObject({ errorI18n: { key: "files_outside_workspace" } });
      expect(await request("pipkin_files_mkdir", root, ".", { name: "a/b" })).toMatchObject({ errorI18n: { key: "files_folder_name_invalid" } });
    });

    it("gives a failed connection's code", async () => {
      const down = await standIn(hosts.down, folder);
      expect(await request("pipkin_files_list", down, ".")).toMatchObject({ entries: [], errorCode: "remote_unreachable", errorI18n: { key: "remote_unreachable" } });
    });
  });

  describe("git", () => {
    let git: ModeHost;
    let repo: string;
    let root: string;
    let clock = 1_700_000_000;

    async function runGit(...args: string[]): Promise<string> {
      const date = `${clock} +0000`;
      const env = { ...process.env, ...ISOLATED, GIT_AUTHOR_NAME: "Test", GIT_AUTHOR_EMAIL: "t@example.com", GIT_COMMITTER_NAME: "Test", GIT_COMMITTER_EMAIL: "t@example.com", GIT_AUTHOR_DATE: date, GIT_COMMITTER_DATE: date };
      return (await exec("git", args, { cwd: repo, env, maxBuffer: 64 * MIB })).stdout;
    }

    async function commitAll(message: string): Promise<string> {
      clock += 60;
      await runGit("add", "-A");
      await runGit("commit", "-q", "-m", message);
      return (await runGit("rev-parse", "HEAD")).trim();
    }

    /** The reply to [type] for the local repo, and then for the same repo through its stand-in. */
    async function both(type: string, fields: JsonObject = {}): Promise<[JsonObject, JsonObject]> {
      return [await git.request(type, { root: repo, ...fields }), await git.request(type, { root, ...fields })];
    }

    beforeAll(async () => {
      repo = path.join(dirs.base, "repo");
      await mkdir(repo);
      await runGit("init", "-q", "-b", "main");
      await writeFile(path.join(repo, "README.md"), "hello\n");
      await writeFile(path.join(repo, "ws.txt"), "a b\n");
      await writeFile(path.join(repo, "a.txt"), "one\n");
      await commitAll("initial");
      await runGit("checkout", "-q", "-b", "feature");
      await writeFile(path.join(repo, "a.txt"), "one\ntwo\n");
      await commitAll("feature one");
      await writeFile(path.join(repo, "bin.dat"), Buffer.from([0, 1, 2]));
      await commitAll("feature two");
      await writeFile(path.join(repo, "a.txt"), "one\ntwo\nthree\n");
      await writeFile(path.join(repo, "ws.txt"), "a  b\n");
      await writeFile(path.join(repo, "it's $x.txt"), "new\n");
      await writeFile(path.join(repo, ".gitignore"), "ignored.txt\n");
      await writeFile(path.join(repo, "ignored.txt"), "ignored\n");
      root = await standIn(hosts.main, repo);
      git = await ModeHost.start("git", dirs, {}, ISOLATED);
    });

    afterAll(() => git.close());

    it("detects the repo as locally, running git only for trusted roots", async () => {
      const [local, remote] = await both("pipkin_git_detect", { trusted: true });
      expect(remote).toEqual(local);
      expect(remote).toEqual({ repo: true, git: true });
      expect(await git.request("pipkin_git_detect", { root, trusted: false })).toEqual({ repo: true, git: true });
      const plain = await standIn(hosts.main, path.join(dirs.base, "files"));
      expect(await git.request("pipkin_git_detect", { root: plain, trusted: true })).toEqual({ repo: false, git: true });
      expect(await git.request("pipkin_git_changes", { root: plain, mode: "uncommitted", ignoreWhitespace: false })).toMatchObject({ code: "not_repo" });
    });

    it("gives the same changes as locally, untracked files included and ignored ones not", async () => {
      for (const ignoreWhitespace of [false, true]) {
        for (const mode of ["uncommitted", "base"]) {
          const [local, remote] = await both("pipkin_git_changes", { mode, ignoreWhitespace });
          expect(remote).toEqual(local);
        }
      }
      const [, remote] = await both("pipkin_git_changes", { mode: "uncommitted", ignoreWhitespace: false });
      expect((remote.files as JsonObject[]).map((file) => file.path)).toEqual([".gitignore", "a.txt", "it's $x.txt", "ws.txt"]);
      expect(remote.branch).toBe("feature");
      const [, hidden] = await both("pipkin_git_changes", { mode: "uncommitted", ignoreWhitespace: true });
      expect((hidden.files as JsonObject[]).map((file) => file.path)).not.toContain("ws.txt");
      const known = remote.fingerprint;
      expect(await git.request("pipkin_git_changes", { root, mode: "uncommitted", ignoreWhitespace: false, known })).toEqual({ fingerprint: known, unchanged: true });
    });

    it("leaves no temporary index behind on the remote", async () => {
      await both("pipkin_git_changes", { mode: "uncommitted", ignoreWhitespace: false });
      expect(await readdir(remoteTmp)).toEqual([]);
    });

    it("gives the same commits and commit diffs as locally", async () => {
      const [local, remote] = await both("pipkin_git_commits");
      expect(remote).toEqual(local);
      expect(remote).toMatchObject({ baseRef: "main", total: 2 });
      for (const commit of remote.commits as JsonObject[]) {
        for (const ignoreWhitespace of [false, true]) {
          const [localDiff, remoteDiff] = await both("pipkin_git_commit_diff", { sha: commit.sha, ignoreWhitespace });
          expect(remoteDiff).toEqual(localDiff);
        }
      }
      expect(await git.request("pipkin_git_commit_diff", { root, sha: "abcdef0", ignoreWhitespace: false })).toMatchObject({ code: "commit_not_found" });
    });

    it("says when the remote has no git", async () => {
      const without = await standIn(hosts.noGit, repo);
      expect(await git.request("pipkin_git_detect", { root: without, trusted: true })).toEqual({ repo: true, git: false });
      expect(await git.request("pipkin_git_changes", { root: without, mode: "uncommitted", ignoreWhitespace: false })).toEqual({
        error: "git isn't installed on nogit.",
        code: "no_git",
        errorI18n: { key: "remote_git_missing", args: { host: "nogit" } },
      });
    });

    it("answers with the failure when the host can't be reached", async () => {
      const down = await standIn(hosts.down, repo);
      expect(await git.request("pipkin_git_detect", { root: down, trusted: true })).toMatchObject({ code: "remote_unreachable" });
      expect(await git.request("pipkin_git_commits", { root: down })).toMatchObject({ code: "remote_unreachable", errorI18n: { key: "remote_unreachable" } });
    });
  });

  /** Runs a terminal in [cwd], types [input], and returns what it showed and its exit line. */
  async function terminal(cwd: string, input: string): Promise<{ output: string; exit: JsonObject }> {
    const { run } = await startHost("terminal", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd, rows: 24, cols: 80 } });
    run.send({ type: "input", data: Buffer.from(input).toString("base64") });
    let output = "";
    for (;;) {
      const message = await run.next();
      if (message === undefined) throw new Error(`The terminal closed without exiting; it showed ${output}`);
      if (message.type === "output") output += Buffer.from(String(message.data), "base64").toString("utf8");
      if (message.type === "pipkin_exit") return { output, exit: message };
    }
  }

  describe("terminal", () => {
    beforeAll(async () => {
      await symlink(FAKE_SCRIPT, path.join(dirs.prefix, "bin", "script"));
    });

    it("runs the remote login shell in the remote folder, as the host's first connection too", async () => {
      await runOp("remote_disconnect", dirs, { id: hosts.main });
      const folder = path.join(dirs.base, "it's a $folder");
      await mkdir(folder);
      const { output, exit } = await terminal(await standIn(hosts.main, folder), `printf '<%s>\\n' "$PWD"; exit 3\n`);
      expect(output).toContain(`<${folder}>`);
      expect(exit).toMatchObject({ code: 3 });
      expect(exit.error).toBeUndefined();
    });

    it("leaves a 255 of the remote shell's own as its exit code while the connection stays up", async () => {
      const { exit } = await terminal(await standIn(hosts.main, "/"), "exit 255\n");
      expect(exit).toMatchObject({ code: 255 });
      expect(exit.error).toBeUndefined();
    });

    it("says the connection was lost when the master ends under the shell", async () => {
      const ssh = path.join(dirs.base, "pipkin", "ssh");
      // The remote is this machine, so the shell can end the master its own session runs over.
      const end = [path.join(dirs.prefix, "bin", "ssh"), "-F", path.join(ssh, "config"), "-o", `ControlPath=${path.join(ssh, "s", hosts.main)}`, "-O", "exit", `pipkin-${hosts.main}`];
      const { output, exit } = await terminal(await standIn(hosts.main, "/"), `${end.map(quote).join(" ")}; sleep 5\n`);
      expect(output).toContain("Couldn't reach main: Connection lost");
      expect(exit).toMatchObject({
        code: 255,
        error: { code: "remote_unreachable", i18n: { key: "remote_unreachable", args: { host: "main", detail: "Connection lost" } } },
      });
    });

    it("says why when the connection fails", async () => {
      const { output, exit } = await terminal(await standIn(hosts.down, "/"), "");
      expect(output).toContain("Couldn't reach down");
      expect(exit).toMatchObject({ code: 255, error: { code: "remote_unreachable", i18n: { key: "remote_unreachable" } } });
    });
  });
});

/** Runs [block] with sessions waiting at most a second for the server to have one free. */
async function briefWait<T>(block: () => Promise<T>): Promise<T> {
  process.env.PIPKIN_REMOTE_SESSION_WAIT_MS = "1000";
  try {
    return await block();
  } finally {
    delete process.env.PIPKIN_REMOTE_SESSION_WAIT_MS;
  }
}

/** [pid] and every process below it, from `ps`. */
async function processTree(pid: number): Promise<number[]> {
  const { stdout } = await exec("ps", ["-A", "-o", "pid=,ppid="]);
  const parents = stdout.trim().split("\n").map((line) => line.trim().split(/\s+/).map(Number));
  const tree = [pid];
  for (let index = 0; index < tree.length; index++) {
    for (const [child, parent] of parents) if (parent === tree[index] && child !== undefined) tree.push(child);
  }
  return tree;
}

describe.skipIf(unavailable !== null)(`a frozen server${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let dirs: Dirs;
  let sshd: TestSshd;
  let id: string;
  let frozen: number[] = [];

  beforeAll(async () => {
    dirs = await shortDirs();
    await linkSshTools(dirs.prefix);
    const key = stringAt(await runOp("remote_key", dirs, {}), "data", "publicKey");
    sshd = await startSshd(path.join(dirs.base, "sshd"), { authorizedKeys: key });
    const host = { name: "frozen", auth: "key", address: `${user}@127.0.0.1`, port: sshd.port };
    id = stringAt(await runOp("remote_host_save", dirs, { host }), "data", "host", "id");
    await writeFile(path.join(dirs.base, "pipkin", "ssh", "known_hosts"), `[127.0.0.1]:${sshd.port} ${sshd.hostKey}\n`);
  });

  afterAll(async () => {
    signalEach(frozen, "SIGCONT");
    await runOp("remote_disconnect", dirs, { id });
    await sshd.stop();
    await rm(dirs.base, { recursive: true, force: true });
  });

  it("fails a listing soon instead of waiting on the stuck master, and ends that master", async () => {
    const browser = await ModeHost.start("browse", dirs, { hostId: id }, { PIPKIN_REMOTE_IDLE_MS: "1000" });
    try {
      expect(await browser.run.next()).toMatchObject({ type: "pipkin_places", places: [{ kind: "remote_home" }, { kind: "remote_root" }] });
      const socket = path.join(dirs.base, "pipkin", "ssh", "s", id);
      await expect(stat(socket)).resolves.toBeDefined();
      // As `docker pause` does: the server and the connection's sshd stop answering, and the master can't tell yet.
      frozen = signalEach(await processTree(sshd.pid), "SIGSTOP");
      const started = Date.now();
      expect(await browser.request("pipkin_list", { path: "/" })).toMatchObject({
        dirs: [],
        errorCode: "remote_unreachable",
        errorI18n: { key: "remote_unreachable", args: { host: "frozen", detail: "Connection timed out" } },
      });
      // One idle limit and one check of the connection, far from the 45 s keepalives would take.
      expect(Date.now() - started).toBeLessThan(10_000);
      await expect(stat(socket)).rejects.toThrow("ENOENT");
    } finally {
      browser.close();
    }
  });
});

describe.skipIf(unavailable !== null)(`a server at its session limit${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let dirs: Dirs;
  let sshd: TestSshd;
  let id: string;
  let remote: Remote;

  beforeAll(async () => {
    dirs = await shortDirs();
    await linkSshTools(dirs.prefix);
    const key = stringAt(await runOp("remote_key", dirs, {}), "data", "publicKey");
    // One session: a running command holds it, so the server refuses any other.
    sshd = await startSshd(path.join(dirs.base, "sshd"), { authorizedKeys: key, maxSessions: 1 });
    const host = { name: "busy", auth: "key", address: `${user}@127.0.0.1`, port: sshd.port };
    id = stringAt(await runOp("remote_host_save", dirs, { host }), "data", "host", "id");
    await writeFile(path.join(dirs.base, "pipkin", "ssh", "known_hosts"), `[127.0.0.1]:${sshd.port} ${sshd.hostKey}\n`);
    const paths = remotePaths(dirs.configDir, dirs.prefix, dirs.home);
    remote = { paths, host: await findHost(paths, id) };
    // Opens the master.
    await runScript(remote, "true");
    // As for a password host: a connection of its own, past the master, can't sign in.
    await writeFile(path.join(dirs.base, "sshd", "authorized_keys"), "");
  });

  afterAll(async () => {
    await runOp("remote_disconnect", dirs, { id });
    await sshd.stop();
    await rm(dirs.base, { recursive: true, force: true });
  });

  /** That the server refused sessions, and that nothing tried a connection besides the master's. */
  function expectOnlyTheMaster(): void {
    expect(sshd.log()).toContain("no more sessions");
    expect(sshd.log()).not.toContain("preauth");
    expect(sshd.log().match(/Accepted publickey/g)).toHaveLength(1);
  }

  it("keeps waiting on a silent command whose connection answers by refusing one more session, and keeps the master", async () => {
    const result = await runScript(remote, "sleep 3; echo done", { idleTimeoutMs: 500 });
    expect(result).toMatchObject({ code: 0 });
    expect(result.stdout.toString("utf8")).toBe("done\n");
    expect(await isConnected(remote)).toBe(true);
    expectOnlyTheMaster();
  });

  it("runs calls the server refuses a session over the master once it has one free, input and all", async () => {
    const input = randomBytes(MIB);
    const before = refusals();
    const [first, second, third] = await Promise.all([
      runScript(remote, "sleep 1; echo one"),
      runScript(remote, "(sha256sum 2>/dev/null || shasum -a 256) | cut -c 1-64", { stdin: input }),
      runScript(remote, "echo three >&2; exit 4"),
    ]);
    expect(first).toMatchObject({ code: 0, stderr: "" });
    expect(first.stdout.toString("utf8")).toBe("one\n");
    expect(second).toMatchObject({ code: 0, stderr: "" });
    expect(second.stdout.toString("utf8")).toBe(`${createHash("sha256").update(input).digest("hex")}\n`);
    expect(third).toMatchObject({ code: 4, stderr: "three\n" });
    expect(refusals()).toBeGreaterThan(before);
    expect(await isConnected(remote)).toBe(true);
    expectOnlyTheMaster();
  });

  /** Runs [script], which prints a line first, and resolves with its run once that line came: its session is open. */
  async function holding(script: string): Promise<{ run: Promise<unknown> }> {
    const { promise: started, resolve } = Promise.withResolvers<void>();
    const run = runScript(remote, script, { onStdout: () => resolve() });
    await started;
    return { run };
  }

  /** How many sessions the server has refused so far. */
  function refusals(): number {
    return sshd.log().match(/no more sessions/g)?.length ?? 0;
  }

  it("stops waiting for a session as soon as the call is aborted", async () => {
    const holder = await holding("echo up; sleep 2");
    const waiting = new AbortController();
    const before = refusals();
    const call = runScript(remote, "echo never", { signal: waiting.signal });
    // Refused once, it waits before asking again.
    await eventually(() => refusals() > before);
    const aborted = Date.now();
    waiting.abort(new Error("Stopped"));
    await expect(call).rejects.toThrow("Stopped");
    expect(Date.now() - aborted).toBeLessThan(300);
    await holder.run;
    expectOnlyTheMaster();
  });

  it("runs a terminal's or a background task's session once the server has one free", async () => {
    const holder = await holding("echo up; sleep 1");
    const before = refusals();
    const { stdout } = await exec(shBinary(remote.paths), ["-c", sessionScript(remote, "echo hi; echo there >&2")]);
    expect(stdout).toBe("hi\n");
    expect(refusals()).toBeGreaterThan(before);
    await holder.run;
    expectOnlyTheMaster();
  });

  it("stops asking for a task's session once the process that started it is gone", async () => {
    const holder = await holding("echo up; sleep 2");
    const ran = path.join(dirs.base, "ran");
    // A Pi that crashes while its task waits, after a refusal or two.
    const owner = spawn("sleep", ["0.5"]).pid ?? 0;
    const before = refusals();
    const waiting = spawn(shBinary(remote.paths), ["-c", sessionScript(remote, `touch ${quote(ran)}`, { owner })], { stdio: "ignore" });
    const [code] = (await once(waiting, "exit")) as [number | null];
    expect(code).toBe(255);
    expect(refusals()).toBeGreaterThan(before);
    await holder.run;
    await expect(stat(ran)).rejects.toThrow("ENOENT");
    expectOnlyTheMaster();
  });

  it("runs a session once though a killed process left its fallback file behind under the same pid", async () => {
    const sockets = path.join(dirs.base, "pipkin", "ssh", "s");
    const runs = path.join(dirs.base, "runs");
    await rm(runs, { force: true });
    // [runScript]'s files are numbered per call: some of the next ones, left by an earlier process with this pid.
    const stale = Array.from({ length: 2000 }, (_, index) => path.join(sockets, `f${String(process.pid)}-${String(index)}`));
    await Promise.all(stale.map((file) => writeFile(file, "")));
    try {
      expect((await runScript(remote, `echo run >> ${quote(runs)}`)).code).toBe(0);
    } finally {
      await Promise.all(stale.map((file) => rm(file, { force: true })));
    }
    // The script's file is named by its pid, which `exec` keeps.
    const script = sessionScript(remote, `echo run >> ${quote(runs)}`);
    await exec(shBinary(remote.paths), ["-c", 'touch "$2$$"; exec sh -c "$1"', "sh", script, path.join(sockets, "f")]);
    expect(await readFile(runs, "utf8")).toBe("run\nrun\n");
  });

  it("fails a call the server keeps refusing a session, saying it has none free", async () => {
    const holder = await holding("echo up; sleep 3");
    const late = path.join(dirs.base, "late-call");
    const started = Date.now();
    await briefWait(async () => {
      await expect(runScript(remote, `touch ${quote(late)}`)).rejects.toMatchObject({
        code: "remote_sessions_full",
        message: "busy has no free sessions. Close a terminal or stop a background task, then try again.",
        i18n: { key: "remote_sessions_full", args: { host: "busy" } },
      });
    });
    expect(Date.now() - started).toBeLessThan(2500);
    await holder.run;
    // Given up on, it never runs once a session is free.
    await expect(stat(late)).rejects.toThrow("ENOENT");
    expectOnlyTheMaster();
  });

  it("ends a terminal's or a background task's session the server keeps refusing, saying why", async () => {
    const holder = await holding("echo up; sleep 4");
    const late = path.join(dirs.base, "late-session");
    const script = await briefWait(() => Promise.resolve(sessionScript(remote, `touch ${quote(late)}`)));
    const failed: unknown = await exec(shBinary(remote.paths), ["-c", script]).catch((error: unknown) => error);
    expect(failed).toMatchObject({ code: 255, stdout: "", stderr: "busy has no free sessions. Close a terminal or stop a background task, then try again.\n" });
    const stderr = (failed as { stderr: string }).stderr;
    expect(sessionFailure(remote, 255, stderr)).toMatchObject({ code: "remote_sessions_full" });
    expect(sessionFailure(remote, 0, stderr)).toBeUndefined();
    await holder.run;
    await expect(stat(late)).rejects.toThrow("ENOENT");
    expectOnlyTheMaster();
  });
});

describe("remote terminal command", () => {
  it("runs ssh with a terminal, in batch mode, then the login shell in the folder", async () => {
    const paths = remotePaths("/data/home/.pipkin/pi", "/data/usr", "/data/home");
    const host: Host = { id: "0123456789ab", name: "box", auth: "key", address: "me@box", port: 22, home: "/home/me" };
    const command = remoteShellCommand({ paths, host }, "/home/me/it's here");
    // Over the master only, then, once there is none, with a master of its own.
    const ssh = `'/data/usr/bin/ssh' '-tt' '-F' '/data/home/.pipkin/ssh/config' '-o' 'ControlPath=/data/home/.pipkin/ssh/s/0123456789ab'`;
    expect(command).toContain(`${ssh} '-o' 'ControlMaster=no' '-o' 'ProxyCommand=touch "$PIPKIN_SSH_FALLBACK"' '-o' 'LogLevel=QUIET'`);
    expect(command).toContain(`exec ${ssh} '-o' 'ControlMaster=auto'`);
    expect(command.match(/'BatchMode=yes' '-o' 'StrictHostKeyChecking=yes' 'pipkin-0123456789ab' -- "\$c"/g)).toHaveLength(2);
    expect(command).toMatch(/^stty rows "\$PIPKIN_ROWS" cols "\$PIPKIN_COLS" && tty > "\$PIPKIN_TTY" && exec 2>"\$PIPKIN_SSH_ERRORS" && unset /);
    await exec("/bin/sh", ["-n", "-c", command]);
    const encoded = /printf %s ([A-Za-z0-9+/=]+) \| base64 -d/.exec(command)?.[1] ?? "";
    expect(Buffer.from(encoded, "base64").toString("utf8")).toBe(`cd -- '/home/me/it'\\''s here' && exec "$SHELL" -l`);
  });
});
