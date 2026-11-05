import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdir, readdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";
import type { JsonObject } from "../src/framing.js";
import type { ModeContext } from "../src/mode.js";
import { isNpmNetworkError, npmCi } from "../src/modes/ensure.js";
import {
  type Dirs,
  installFakePi,
  makeDirs,
  PACKAGE_JSON,
  piDir,
  PI_VERSION,
  removeDirs,
  runMode,
  startHost,
  writeRuntimeManifest,
} from "./harness.js";

describe("npm network errors", () => {
  it("recognizes npm's reports of an unreachable registry and nothing else", () => {
    // Lines npm printed on the emulator with Wi-Fi and mobile data off.
    expect(isNpmNetworkError("npm error network In most cases you are behind a proxy or have bad network settings.")).toBe(true);
    expect(isNpmNetworkError("npm error code EAI_AGAIN")).toBe(true);
    expect(isNpmNetworkError("npm error code ECONNRESET")).toBe(true);
    expect(isNpmNetworkError("npm error code EINTEGRITY")).toBe(false);
    expect(isNpmNetworkError("npm error code ENOSPC")).toBe(false);
    expect(isNpmNetworkError("added 1 package: network-utils")).toBe(false);
  });
});

const PACKAGE_LOCK = JSON.stringify({ name: "pipkin-pi-runtime", lockfileVersion: 3 });

function installRequest(params: { reinstall?: boolean } = {}): JsonObject {
  return { packageJson: PACKAGE_JSON, packageLock: PACKAGE_LOCK, expectedVersion: PI_VERSION, pipkinExtensions: [], ...params };
}

/**
 * A stand-in `npm` that runs for `$NPM_SECONDS` and, like npm, takes a moment to roll back once it's told to stop. It
 * logs its start only once it's ready to roll back.
 */
const ROLLING_BACK_NPM = [
  'sleep "${NPM_SECONDS:-0}" >/dev/null 2>&1 & pause=$!',
  'trap \'kill $pause; sleep 1; echo "end $$" >> npm-runs; exit 143\' TERM',
  'echo "start $$" >> npm-runs',
  "wait $pause",
  'echo "end $$" >> npm-runs',
].join("\n");

describe("ensure mode", () => {
  let dirs: Dirs;

  /** Runs ensure against the matching install from `beforeEach`, so it skips npm. */
  function ensure(pipkinExtensions: unknown[] = []): Promise<unknown> {
    return runMode("ensure", dirs, { packageJson: PACKAGE_JSON, packageLock: PACKAGE_LOCK, expectedVersion: PI_VERSION, pipkinExtensions });
  }

  /** Puts a stand-in `npm` that runs [script] in `pi/` first on the host's PATH, and returns that PATH. */
  async function stubNpm(script: string): Promise<{ PATH: string }> {
    const bin = path.join(dirs.base, "bin");
    await mkdir(bin, { recursive: true });
    await writeFile(path.join(bin, "npm"), `#!/bin/sh\n${script}\n`, { mode: 0o755 });
    return { PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}` };
  }

  /** Runs ensure with a stand-in `npm` that records each run in `pi/npm-runs` and exits with [npmExit]. */
  async function ensureWithNpm(params: { reinstall?: boolean } = {}, npmExit = 0): Promise<unknown> {
    return runMode("ensure", dirs, installRequest(params), await stubNpm(`echo "$*" >> npm-runs\nexit ${npmExit}`));
  }

  async function npmRuns(): Promise<number> {
    const runs = await readFile(path.join(dirs.root, "pi/npm-runs"), "utf8").catch(() => "");
    return runs.split("\n").filter((line) => line.startsWith("ci ")).length;
  }

  beforeEach(async () => {
    dirs = await makeDirs();
    // An install that already matches, so ensure skips npm.
    await installFakePi(dirs);
    await writeRuntimeManifest(dirs);
    await writeFile(path.join(dirs.root, "pi/package-lock.json"), PACKAGE_LOCK);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("leaves terminal Pi's config and shell profile alone", async () => {
    const terminalPi = path.join(dirs.home, ".pi/agent");
    await mkdir(terminalPi, { recursive: true });
    await writeFile(path.join(terminalPi, "settings.json"), JSON.stringify({ defaultProvider: "anthropic" }));
    const bashrc = path.join(dirs.home, ".bashrc");
    await writeFile(bashrc, "alias ll='ls -l'\n");

    const result = await ensure();

    expect(result).toMatchObject({ ok: true, data: { piVersion: PI_VERSION } });
    expect(await readFile(bashrc, "utf8")).toBe("alias ll='ls -l'\n");
    expect(await readdir(dirs.configDir)).toEqual([]);
  });

  it("installs an extension that uses runtime packages inside the runtime, and any other outside it", async () => {
    const result = await ensure([
      { name: "pipkin-todo", source: "todo\n", usesRuntimePackages: false },
      { name: "pipkin-web-access", source: "web\n", usesRuntimePackages: true },
    ]);

    expect(result).toMatchObject({ ok: true });
    expect(await readFile(path.join(dirs.root, "extensions/pipkin-todo/index.js"), "utf8")).toBe("todo\n");
    expect(await readFile(path.join(dirs.root, "pi/extensions/pipkin-web-access/index.js"), "utf8")).toBe("web\n");
  });

  it("replaces an extension an older Pipkin installed", async () => {
    const file = path.join(dirs.root, "extensions/pipkin-todo/index.js");
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, "old\n");

    await ensure([{ name: "pipkin-todo", source: "new\n", usesRuntimePackages: false }]);

    expect(await readFile(file, "utf8")).toBe("new\n");
  });

  it("refuses an extension name that is not a plain folder name", async () => {
    const result = await ensure([{ name: "../escape", source: "x", usesRuntimePackages: false }]);

    expect(result).toMatchObject({ ok: false, code: "bad_params" });
    expect(await readdir(dirs.root)).not.toContain("escape");
  });

  describe("with remote hosts saved", () => {
    const host = { id: "0123456789ab", name: "devbox", auth: "key", address: "me@devbox", port: 22, home: null };

    it("rewrites ssh/config an older Pipkin wrote, so its settings reach saved hosts", async () => {
      const ssh = path.join(path.dirname(dirs.configDir), "ssh");
      await mkdir(ssh, { recursive: true });
      await writeFile(path.join(ssh, "hosts.json"), JSON.stringify({ version: 1, hosts: [host] }));
      await writeFile(path.join(ssh, "config"), "Host *\n  ServerAliveCountMax 3\n");

      expect(await ensure()).toMatchObject({ ok: true });

      const config = await readFile(path.join(ssh, "config"), "utf8");
      expect(config).toContain("ServerAliveCountMax 2");
      expect(config).toContain("Host pipkin-0123456789ab");
    });

    it("still succeeds when the hosts file can't be read", async () => {
      const ssh = path.join(path.dirname(dirs.configDir), "ssh");
      await mkdir(ssh, { recursive: true });
      await writeFile(path.join(ssh, "hosts.json"), "{not json");

      expect(await ensure()).toMatchObject({ ok: true, data: { piVersion: PI_VERSION } });
    });
  });

  describe("installing the runtime", () => {
    it("skips npm when the runtime is installed in full", async () => {
      expect(await ensureWithNpm()).toMatchObject({ ok: true });
      expect(await npmRuns()).toBe(0);
    });

    it.each([
      ["Pi's CLI script is missing", (target: Dirs) => rm(path.join(piDir(target), "dist/bundle/cli.js"))],
      ["an earlier install was cut off", (target: Dirs) => writeFile(path.join(target.root, "pi/.pipkin-installing"), "")],
    ])("installs again when %s, though the manifests match", async (_, damage) => {
      await damage(dirs);
      expect(await ensureWithNpm()).toMatchObject({ ok: true });
      expect(await npmRuns()).toBe(1);
      await expect(readFile(path.join(dirs.root, "pi/.pipkin-installing"))).rejects.toMatchObject({ code: "ENOENT" });
    });

    it("installs a complete runtime again when asked to reinstall", async () => {
      expect(await ensureWithNpm({ reinstall: true })).toMatchObject({ ok: true });
      expect(await npmRuns()).toBe(1);
    });

    it("leaves a failed install marked unfinished, so the next one runs npm again", async () => {
      await rm(path.join(dirs.root, "pi/package-lock.json"));
      expect(await ensureWithNpm({}, 1)).toMatchObject({ ok: false, code: "npm_failed" });
      expect(await readFile(path.join(dirs.root, "pi/package-lock.json"), "utf8")).toBe(PACKAGE_LOCK);

      expect(await ensureWithNpm()).toMatchObject({ ok: true });
      expect(await npmRuns()).toBe(2);
    });

    it("starts an install only once the npm of a cancelled one has exited", async () => {
      await writeFile(path.join(dirs.root, "pi/.pipkin-installing"), "");
      const env = await stubNpm(ROLLING_BACK_NPM);
      const runs = path.join(dirs.root, "pi/npm-runs");
      const { run } = await startHost("ensure", dirs, { ...env, NPM_SECONDS: "30" });
      await run.next();
      run.send({ type: "pipkin_request", params: installRequest() });
      while (!(await readFile(runs, "utf8").catch(() => "")).includes("start")) await sleep(50);
      run.socket.destroy();

      expect(await runMode("ensure", dirs, installRequest(), env)).toMatchObject({ ok: true });
      const events = (await readFile(runs, "utf8")).trim().split("\n");
      expect(events.map((line) => line.split(" ")[0])).toEqual(["start", "end", "start", "end"]);
      expect(events[1]).toBe(events[0]?.replace("start", "end"));
    });

    it("stops npm and waits for it to exit when it can't record it, so the lock isn't given up while npm runs", async () => {
      const { PATH } = await stubNpm(ROLLING_BACK_NPM);
      vi.stubEnv("PATH", PATH);
      vi.stubEnv("NPM_SECONDS", "30");
      onTestFinished(() => {
        vi.unstubAllEnvs();
      });
      const cwd = path.join(dirs.root, "pi");
      const runs = path.join(cwd, "npm-runs");
      // Only what npmCi uses: it reports progress and stops npm when the app goes away.
      const ctx = { conn: { send: () => undefined }, signal: new AbortController().signal } as unknown as ModeContext;
      const lock = {
        // Fails once npm is running, as a full disk or a changed folder would.
        async recordNpm(): Promise<void> {
          while (!(await readFile(runs, "utf8").catch(() => "")).includes("start")) await sleep(20);
          throw new Error("can't record npm");
        },
      };

      await expect(npmCi(ctx, cwd, lock)).rejects.toThrow("can't record npm");
      const events = (await readFile(runs, "utf8")).trim().split("\n");
      expect(events.map((line) => line.split(" ")[0])).toEqual(["start", "end"]);
    });

    it("takes over an install lock whose holder and npm are gone", async () => {
      const gone = spawn("true");
      await once(gone, "exit");
      await symlink(String(gone.pid), path.join(dirs.root, "install.lock"));
      await symlink(String(gone.pid), path.join(dirs.root, "install.lock.npm"));

      expect(await ensureWithNpm({ reinstall: true })).toMatchObject({ ok: true });
      expect(await npmRuns()).toBe(1);
      const left = await readdir(dirs.root);
      expect(left).not.toContain("install.lock");
      expect(left).not.toContain("install.lock.npm");
    });

    it("waits while a live host holds the install lock, though the npm it recorded has exited", async () => {
      const gone = spawn("true");
      await once(gone, "exit");
      await symlink(String(gone.pid), path.join(dirs.root, "install.lock.npm"));
      // A host whose npm has exited and that is still finishing its install, such as removing its marker.
      const holder = spawn(process.execPath, ["-e", "setInterval(() => {}, 60_000)", "host.mjs"], { stdio: "ignore" });
      await once(holder, "spawn");
      onTestFinished(() => {
        holder.kill();
      });
      await symlink(String(holder.pid), path.join(dirs.root, "install.lock"));

      const install = ensureWithNpm({ reinstall: true });
      // A real delay: the host under test is another process, polling the lock every 500 ms; three polls must pass
      // without npm starting.
      await sleep(1500);
      expect(await npmRuns()).toBe(0);
      holder.kill();
      await once(holder, "exit");
      expect(await install).toMatchObject({ ok: true });
      expect(await npmRuns()).toBe(1);
    });
  });
});
