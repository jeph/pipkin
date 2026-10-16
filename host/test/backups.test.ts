import { createWriteStream } from "node:fs";
import { access, chmod, lstat, mkdir, readdir, readFile, readlink, rm, stat, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { setTimeout as sleep } from "node:timers/promises";
import { createGzip } from "node:zlib";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { isObject } from "../src/framing.js";
import { tarStream, type TarEntry } from "../src/tar.js";
import { type Dirs, makeDirs, removeDirs, runMode } from "./harness.js";

/** Every entry under `dir`: file contents, symlink targets, folders, with permission bits. */
async function snapshot(dir: string): Promise<Record<string, string>> {
  const result: Record<string, string> = {};
  for (const name of (await readdir(dir, { recursive: true })).toSorted()) {
    const full = path.join(dir, name);
    const stats = await lstat(full);
    const mode = (stats.mode & 0o777).toString(8);
    if (stats.isSymbolicLink()) result[name] = `link -> ${await readlink(full)}`;
    else if (stats.isDirectory()) result[name] = `dir ${mode}`;
    else result[name] = `file ${mode} ${await readFile(full, "utf8")}`;
  }
  return result;
}

async function* list(entries: TarEntry[]): AsyncGenerator<TarEntry> {
  yield* entries;
}

function file(entryPath: string, content: string): TarEntry {
  return { type: "file", path: entryPath, mode: 0o644, mtime: 0, size: Buffer.byteLength(content), content: Readable.from([Buffer.from(content)]) };
}

const MANIFEST = JSON.stringify({ format: "pipkin-config-backup", version: 1, createdAt: "2026-01-01T00:00:00.000Z", piVersion: null });

describe("configuration backups", () => {
  let dirs: Dirs;
  let backups: string;

  beforeEach(async () => {
    dirs = await makeDirs();
    backups = path.join(dirs.base, "backups");
    const config = dirs.configDir;
    await mkdir(path.join(config, "extensions/empty"), { recursive: true });
    await mkdir(path.join(config, "npm/node_modules/tool/bin"), { recursive: true });
    await mkdir(path.join(config, "npm/node_modules/.bin"), { recursive: true });
    await mkdir(path.join(config, `skills/${"long-skill-name-".repeat(8)}`), { recursive: true });
    await mkdir(path.join(config, "sessions/--home--"), { recursive: true });
    await mkdir(path.join(config, "bin"), { recursive: true });
    await mkdir(path.join(config, "tmp"), { recursive: true });
    await writeFile(path.join(config, "settings.json"), '{"theme":"dark"}');
    await writeFile(path.join(config, "auth.json"), '{"anthropic":{}}', { mode: 0o600 });
    await chmod(path.join(config, "auth.json"), 0o600);
    await writeFile(path.join(config, "AGENTS.md"), "# Rules\n");
    await writeFile(path.join(config, "npm/node_modules/tool/bin/tool.js"), "#!/usr/bin/env node\n");
    await chmod(path.join(config, "npm/node_modules/tool/bin/tool.js"), 0o755);
    await symlink("../tool/bin/tool.js", path.join(config, "npm/node_modules/.bin/tool"));
    await writeFile(path.join(config, `skills/${"long-skill-name-".repeat(8)}/SKILL.md`), "skill\n");
    await writeFile(path.join(config, "sessions/--home--/a.jsonl"), "session a\n");
    await writeFile(path.join(config, "bin/rg"), "binary");
    await writeFile(path.join(config, "tmp/scratch"), "scratch");
    await writeFile(path.join(config, "pi-debug.log"), "log");
    await writeFile(path.join(config, "crashes.json"), "[]");
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  async function backUp(env: Record<string, string> = {}): Promise<string> {
    const result = await runMode("backup_create", dirs, {}, env);
    const data = result.data;
    if (result.ok !== true || !isObject(data) || !isObject(data.backup) || typeof data.backup.name !== "string") {
      throw new Error(`backup_create failed: ${JSON.stringify(result)}`);
    }
    return data.backup.name;
  }

  async function writeArchive(name: string, entries: TarEntry[]): Promise<void> {
    await mkdir(backups, { recursive: true });
    await pipeline(Readable.from(tarStream(list(entries))), createGzip(), createWriteStream(path.join(backups, name)));
  }

  it("restores the configuration as it was backed up, leaving sessions, caches and logs alone", async () => {
    const backedUp = await snapshot(dirs.configDir);
    const name = await backUp();
    expect(name).toMatch(/^pipkin-config-\d{8}T\d{6}Z\.tar\.gz$/);

    // An agent breaks the configuration, while chats and Pi's own files carry on changing.
    await writeFile(path.join(dirs.configDir, "settings.json"), "{ broken");
    await rm(path.join(dirs.configDir, "AGENTS.md"));
    await rm(path.join(dirs.configDir, "npm"), { recursive: true });
    await writeFile(path.join(dirs.configDir, "added-later.json"), "{}");
    await writeFile(path.join(dirs.configDir, "sessions/--home--/a.jsonl"), "session a\nnewer message\n");
    await writeFile(path.join(dirs.configDir, "sessions/--home--/b.jsonl"), "session b\n");
    await writeFile(path.join(dirs.configDir, "bin/rg"), "updated binary");
    await writeFile(path.join(dirs.configDir, "pi-debug.log"), "more log");

    expect(await runMode("backup_restore", dirs, { name })).toEqual({ type: "pipkin_result", ok: true, data: {} });
    expect(await snapshot(dirs.configDir)).toEqual({
      ...backedUp,
      "sessions/--home--/a.jsonl": "file 644 session a\nnewer message\n",
      "sessions/--home--/b.jsonl": "file 644 session b\n",
      "bin/rg": "file 644 updated binary",
      "pi-debug.log": "file 644 more log",
    });
    expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "root"]);
  });

  it("restores into a config folder that no longer exists", async () => {
    const backedUp = await snapshot(dirs.configDir);
    const name = await backUp();
    await rm(dirs.configDir, { recursive: true });
    expect(await runMode("backup_restore", dirs, { name })).toMatchObject({ ok: true });
    expect(await snapshot(dirs.configDir)).toEqual(
      Object.fromEntries(Object.entries(backedUp).filter(([entry]) => !/^(sessions|bin|tmp|pi-debug\.log|crashes\.json)/.test(entry))),
    );
  });

  it("lists backups newest first, with what their manifests say and which files are not backups", async () => {
    const first = await backUp();
    await sleep(1100);
    const second = await backUp();
    await writeFile(path.join(backups, "notes.tar.gz"), "not gzip");
    await writeFile(path.join(backups, ".hidden.tar.gz"), "ignored");
    await writeFile(path.join(backups, "readme.txt"), "ignored");
    // The file that isn't a backup is dated by its modification time, so it is the newest.
    expect(await runMode("backups", dirs, {})).toMatchObject({
      ok: true,
      data: {
        dir: backups,
        backups: [
          { name: "notes.tar.gz", error: expect.any(String) },
          { name: second, error: null, piVersion: null, size: expect.any(Number) },
          { name: first, error: null },
        ],
      },
    });
  });

  it("lists nothing before the first backup", async () => {
    expect(await runMode("backups", dirs, {})).toMatchObject({ ok: true, data: { backups: [] } });
  });

  it("gives a second backup in the same second its own name", async () => {
    const first = await backUp();
    const second = await backUp();
    expect(second).not.toBe(first);
    expect((await readdir(backups)).toSorted()).toEqual([first, second].toSorted());
  });

  it("changes nothing when the backup is damaged", async () => {
    const name = await backUp();
    const archive = path.join(backups, name);
    await writeFile(path.join(dirs.configDir, "settings.json"), '{"changed":true}');
    const before = await snapshot(dirs.configDir);

    const bytes = await readFile(archive);
    await writeFile(archive, bytes.subarray(0, Math.floor(bytes.length / 2)));
    expect(await runMode("backup_restore", dirs, { name })).toMatchObject({ ok: false, code: "bad_backup" });

    bytes[Math.floor(bytes.length / 2)] = (bytes[Math.floor(bytes.length / 2)] as number) ^ 0xff;
    await writeFile(archive, bytes);
    expect(await runMode("backup_restore", dirs, { name })).toMatchObject({ ok: false, code: "bad_backup" });

    expect(await snapshot(dirs.configDir)).toEqual(before);
    expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "root"]);
  });

  it.each([
    ["a path that climbs out", [file("config/../escape.json", "{}")]],
    ["an absolute path", [file("/etc/escape.json", "{}")]],
    ["a file outside config/", [file("other/settings.json", "{}")]],
    ["the sessions folder", [file("config/sessions/--home--/a.jsonl", "replaced")]],
    ["Pi's downloaded tools", [file("config/bin/rg", "replaced")]],
    ["the same path twice", [file("config/settings.json", "1"), file("config/settings.json", "2")]],
    [
      "a file beneath a symlink",
      [{ type: "symlink", path: "config/link", target: "/tmp", mtime: 0 } as TarEntry, file("config/link/escape.json", "{}")],
    ],
    [
      "a symlink over a folder it already holds files in",
      [file("config/folder/a.json", "{}"), { type: "symlink", path: "config/folder", target: "/tmp", mtime: 0 } as TarEntry],
    ],
  ])("refuses a backup holding %s and changes nothing", async (_, entries) => {
    await writeArchive("crafted.tar.gz", [file("manifest.json", MANIFEST), ...entries]);
    const before = await snapshot(dirs.configDir);
    expect(await runMode("backup_restore", dirs, { name: "crafted.tar.gz" })).toMatchObject({ ok: false, code: "bad_backup" });
    expect(await snapshot(dirs.configDir)).toEqual(before);
    expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "root"]);
  });

  it("refuses an archive without Pipkin's manifest", async () => {
    await writeArchive("other.tar.gz", [file("config/settings.json", "{}")]);
    expect(await runMode("backup_restore", dirs, { name: "other.tar.gz" })).toMatchObject({ ok: false, code: "not_backup" });
  });

  it("refuses a backup from a newer backup format", async () => {
    await writeArchive("newer.tar.gz", [file("manifest.json", JSON.stringify({ format: "pipkin-config-backup", version: 2 }))]);
    expect(await runMode("backup_restore", dirs, { name: "newer.tar.gz" })).toMatchObject({ ok: false, code: "backup_version" });
  });

  it("restores symlinks that point outside the config folder as they were", async () => {
    await writeArchive("links.tar.gz", [
      file("manifest.json", MANIFEST),
      { type: "symlink", path: "config/skills/mine", target: "/home/user/projects/skill", mtime: 0 },
    ]);
    expect(await runMode("backup_restore", dirs, { name: "links.tar.gz" })).toMatchObject({ ok: true });
    expect(await readlink(path.join(dirs.configDir, "skills/mine"))).toBe("/home/user/projects/skill");
  });

  it("only accepts plain backup names from the backups folder", async () => {
    for (const name of ["../config/settings.json", "sub/x.tar.gz", ".hidden.tar.gz", "x.zip"]) {
      expect(await runMode("backup_restore", dirs, { name })).toMatchObject({ ok: false, code: "bad_name" });
      expect(await runMode("backup_delete", dirs, { name })).toMatchObject({ ok: false, code: "bad_name" });
    }
    expect(await runMode("backup_restore", dirs, { name: "missing.tar.gz" })).toMatchObject({ ok: false, code: "not_found" });
  });

  it("deletes a backup, and counts one that is already gone as deleted", async () => {
    const name = await backUp();
    expect(await runMode("backup_delete", dirs, { name })).toMatchObject({ ok: true });
    expect(await readdir(backups)).toEqual([]);
    expect(await runMode("backup_delete", dirs, { name })).toMatchObject({ ok: true });
  });

  it("finishes a restore that a crash cut short once its backup was unpacked", async () => {
    const staging = `${dirs.configDir}.restore-1`;
    const old = `${dirs.configDir}.old-1`;
    await mkdir(staging);
    await writeFile(path.join(staging, "settings.json"), '{"restored":true}');
    await writeFile(`${staging}.complete`, "");
    // The crash came after AGENTS.md moved aside but before the rest did.
    await mkdir(old);
    await writeFile(path.join(old, "AGENTS.md"), "# Rules\n");
    await rm(path.join(dirs.configDir, "AGENTS.md"));

    expect(await runMode("backup_create", dirs, {})).toMatchObject({ ok: true });
    const entries = (await readdir(dirs.configDir)).toSorted();
    expect(entries).toEqual(["bin", "crashes.json", "pi-debug.log", "sessions", "settings.json", "tmp"]);
    expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"restored":true}');
    expect(await readFile(path.join(dirs.configDir, "sessions/--home--/a.jsonl"), "utf8")).toBe("session a\n");
    expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "root"]);
  });

  it("discards a restore that a crash cut short while unpacking", async () => {
    const before = await snapshot(dirs.configDir);
    const staging = `${dirs.configDir}.restore-1`;
    await mkdir(staging);
    await writeFile(path.join(staging, "settings.json"), "{ half");
    expect(await runMode("backup_create", dirs, {})).toMatchObject({ ok: true });
    expect(await snapshot(dirs.configDir)).toEqual(before);
    expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "root"]);
  });

  it("removes a backup a crash left half-written", async () => {
    await mkdir(backups);
    await writeFile(path.join(backups, ".pipkin-config-x.tar.gz.partial-123"), "half");
    const name = await backUp();
    expect(await readdir(backups)).toEqual([name]);
  });

  it("refuses to run while another host holds the config lock, and takes over one a dead host left", async () => {
    await symlink(String(process.pid), path.join(dirs.root, "config.lock"));
    expect(await runMode("backup_create", dirs, {})).toMatchObject({ ok: false, code: "busy" });
    await rm(path.join(dirs.root, "config.lock"));
    await symlink("999999", path.join(dirs.root, "config.lock"));
    expect(await runMode("backup_create", dirs, {})).toMatchObject({ ok: true });
    expect(await readdir(dirs.root)).toEqual([]);
  });

  describe("with Pi running on the config folder", () => {
    let proc: string;

    beforeEach(async () => {
      proc = path.join(dirs.base, "proc");
      await mkdir(path.join(proc, "4242"), { recursive: true });
      await writeFile(path.join(proc, "4242/cmdline"), "node\0/root/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js\0--mode\0rpc\0");
      await writeFile(path.join(proc, "4242/environ"), `HOME=/home\0PI_CODING_AGENT_DIR=${dirs.configDir}\0PATH=/bin\0`);
    });

    it("waits for Pi to exit before swapping the configuration in", async () => {
      const name = await backUp();
      await writeFile(path.join(dirs.configDir, "settings.json"), "{ broken");
      const restore = runMode("backup_restore", dirs, { name }, { PIPKIN_PROC_DIR: proc });
      await sleep(1000);
      expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe("{ broken");
      await rm(path.join(proc, "4242"), { recursive: true });
      expect(await restore).toMatchObject({ ok: true });
      expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
    });

    it("finishes a restore a crash cut short only once Pi has exited", async () => {
      const staging = `${dirs.configDir}.restore-1`;
      await mkdir(staging);
      await writeFile(path.join(staging, "settings.json"), '{"restored":true}');
      await writeFile(`${staging}.complete`, "");
      // A backup runs beside open chats, so it leaves the swap alone rather than wait for them.
      const name = await backUp({ PIPKIN_PROC_DIR: proc });
      expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
      await access(`${staging}.complete`);
      // A restore waits for Pi to exit, then finishes the earlier restore before unpacking its own.
      const restore = runMode("backup_restore", dirs, { name }, { PIPKIN_PROC_DIR: proc });
      await sleep(1000);
      expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
      await rm(path.join(proc, "4242"), { recursive: true });
      expect(await restore).toMatchObject({ ok: true });
      expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe('{"theme":"dark"}');
      expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "proc", "root"]);
    });

    it("ignores Pi running on another config folder and other programs", async () => {
      await writeFile(path.join(proc, "4242/environ"), "PI_CODING_AGENT_DIR=/elsewhere\0");
      await mkdir(path.join(proc, "4343"));
      await writeFile(path.join(proc, "4343/cmdline"), "bash\0");
      await writeFile(path.join(proc, "4343/environ"), `PI_CODING_AGENT_DIR=${dirs.configDir}\0`);
      const name = await backUp();
      expect(await runMode("backup_restore", dirs, { name }, { PIPKIN_PROC_DIR: proc })).toMatchObject({ ok: true });
    });

    it("gives up, changing nothing, when Pi does not exit", { timeout: 40_000 }, async () => {
      const name = await backUp();
      await writeFile(path.join(dirs.configDir, "settings.json"), "{ broken");
      expect(await runMode("backup_restore", dirs, { name }, { PIPKIN_PROC_DIR: proc })).toMatchObject({ ok: false, code: "pi_running" });
      expect(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")).toBe("{ broken");
      expect((await readdir(dirs.base)).toSorted()).toEqual(["backups", "config", "home", "prefix", "proc", "root"]);
    });
  });

  it("keeps the backup's file permissions", async () => {
    const name = await backUp();
    await chmod(path.join(dirs.configDir, "auth.json"), 0o644);
    await runMode("backup_restore", dirs, { name });
    expect((await stat(path.join(dirs.configDir, "auth.json"))).mode & 0o777).toBe(0o600);
    expect((await stat(path.join(dirs.configDir, "npm/node_modules/tool/bin/tool.js"))).mode & 0o777).toBe(0o755);
  });
});
