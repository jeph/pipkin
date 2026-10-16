import { access, mkdir, readFile, symlink, writeFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { encodeLine } from "../src/framing.js";
import { SYSTEM_APPEND } from "../src/prompt.js";
import {
  type Dirs,
  type HostRun,
  installFakePi,
  makeDirs,
  PACKAGE_JSON,
  PI_VERSION,
  removeDirs,
  runtimeManifest,
  startHost,
  writeRuntimeManifest,
} from "./harness.js";

const MiB = 1024 * 1024;

/** Stands in for Termux's `$PREFIX/bin/login`: runs [startup] the way a profile and `~/.bashrc` would, then the command. */
async function writeLogin(dirs: Dirs, startup: string): Promise<void> {
  await mkdir(path.join(dirs.prefix, "bin"), { recursive: true });
  await writeFile(path.join(dirs.prefix, "bin/login"), `#!/bin/sh\n[ "$1" = -i ] && shift\n${startup}\nexec /bin/sh "$@"\n`, { mode: 0o755 });
}

/** True once `file` exists, polling for up to `timeoutMs`. */
async function appears(file: string, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await access(file).then(() => true, () => false)) return true;
    await sleep(50);
  }
  return false;
}

/** Reads lines through `flood_end` and checks every flood line arrived, in order. */
async function expectWholeFlood(run: HostRun): Promise<void> {
  const seqs: unknown[] = [];
  let end = await run.next();
  while (end?.type === "flood") {
    seqs.push(end.seq);
    end = await run.next();
  }
  expect(end).toEqual({ type: "flood_end", count: seqs.length });
  expect(seqs).toEqual(seqs.map((_, index) => index));
  expect(seqs.length).toBeGreaterThan(0);
}

/** Where the host writes the pipkin-todo extension for Pi. */
function todoExtension(dirs: Dirs): string {
  return path.join(dirs.root, "extensions/pipkin-todo/index.js");
}

/** Starts a chat with `packageJson` as the runtime manifest and `params` in its request, and ends it once Pi is ready. */
async function launchChat(dirs: Dirs, packageJson = PACKAGE_JSON, params: Record<string, unknown> = {}): Promise<void> {
  await writeRuntimeManifest(dirs, packageJson);
  const { run } = await startHost("rpc", dirs);
  await run.next();
  run.send({
    type: "pipkin_request",
    params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson, disabledPackages: [], pipkinExtensions: [], ...params },
  });
  expect(await run.next()).toMatchObject({ type: "ready" });
  run.socket.end();
  await run.exited;
}

/** Starts a chat with `packages` bundled and returns `web-search.json` as Pi then finds it, or null when absent. */
async function webSearchConfigAfterLaunch(dirs: Dirs, packages: string[], disabledPackages: string[] = []): Promise<string | null> {
  await launchChat(dirs, runtimeManifest(packages), { disabledPackages });
  return readFile(path.join(dirs.configDir, "web-search.json"), "utf8").catch(() => null);
}

describe("rpc mode", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installFakePi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("says hello with the token and environment", async () => {
    const { run, token } = await startHost("rpc", dirs);
    expect(await run.next()).toEqual({
      type: "pipkin_hello",
      protocol: 1,
      token,
      mode: "rpc",
      piVersion: PI_VERSION,
      nodeVersion: process.versions.node,
      configDir: dirs.configDir,
      home: dirs.home,
    });
    run.socket.end();
    await run.exited;
  });

  it("pipes bytes both ways, forwarding bytes sent with the request line", async () => {
    const { run } = await startHost("rpc", dirs, { FAKE_PI_EXIT_CODE: "3" });
    await run.next();
    const first = { id: "1", type: "prompt", message: "line\u2028separator" };
    run.socket.write(
      encodeLine({
        type: "pipkin_request",
        params: { cwd: dirs.home, sessionPath: "/s.jsonl", expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] },
      }) + encodeLine(first),
    );
    expect(await run.next()).toEqual({
      type: "ready",
      argv: ["--mode", "rpc", "--session", "/s.jsonl", "--append-system-prompt", SYSTEM_APPEND],
      cwd: dirs.home,
      env: { PI_CODING_AGENT_DIR: dirs.configDir },
    });
    expect(await run.next()).toEqual({ type: "echo", command: first });
    const second = { id: "2", type: "get_state" };
    run.send(second);
    expect(await run.next()).toEqual({ type: "echo", command: second });

    run.socket.end();
    const exit = await run.next();
    expect(exit).toMatchObject({ type: "pipkin_exit", code: 3, signal: null });
    expect(exit?.stderr).toBe("fake-pi ready\nfake-pi stdin closed\n");
    expect(await run.next()).toBeUndefined();
    expect(await run.exited).toBe(0);
  });

  it("ends Pi's stdin when the app closes the socket, and Pi exits", async () => {
    const marker = path.join(dirs.base, "eof");
    const { run } = await startHost("rpc", dirs, { FAKE_PI_MARKER: marker });
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "ready" });
    run.socket.destroy();
    // The host exits only after Pi exited, and fake-pi writes the marker on stdin EOF.
    expect(await run.exited).toBe(0);
    await expect(access(marker)).resolves.toBeUndefined();
  });

  // Android can freeze Pipkin while the screen is off; Pi must keep working rather than wait for it to read.
  it("keeps Pi writing while the app is not reading", async () => {
    const done = path.join(dirs.base, "flood-done");
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "ready" });

    run.socket.pause();
    run.send({ type: "flood", bytes: 8 * MiB, done });
    expect(await appears(done, 10_000)).toBe(true);

    run.socket.resume();
    await expectWholeFlood(run);
    run.socket.end();
    expect(await run.exited).toBe(0);
  });

  it("makes Pi wait once the unread output reaches the backlog limit, then delivers all of it", async () => {
    const done = path.join(dirs.base, "flood-done");
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "ready" });

    run.socket.pause();
    run.send({ type: "flood", bytes: 48 * MiB, done });
    expect(await appears(done, 3000)).toBe(false);

    run.socket.resume();
    await expectWholeFlood(run);
    expect(await appears(done, 10_000)).toBe(true);
    run.socket.end();
    expect(await run.exited).toBe(0);
  });

  // Pi blocks writing to its stdout, so only dropping the output lets it reach stdin EOF before the SIGTERM fallback.
  it("lets a waiting Pi exit promptly once the app hangs up", async () => {
    const done = path.join(dirs.base, "flood-done");
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "ready" });

    run.socket.pause();
    run.send({ type: "flood", bytes: 48 * MiB, done });
    expect(await appears(done, 3000)).toBe(false);

    const hungUpAt = Date.now();
    run.socket.destroy();
    expect(await run.exited).toBe(0);
    expect(Date.now() - hungUpAt).toBeLessThan(3000);
  });

  it("refuses a Pi version other than the expected one", async () => {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: "9.9.9", packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "pi_version" });
    expect(await run.next()).toBeUndefined();
  });

  it("refuses to start Pi while a backup or restore holds the config lock", async () => {
    await symlink(String(process.pid), path.join(dirs.root, "config.lock"));
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "config_busy" });
    expect(await run.next()).toBeUndefined();
  });

  it("loads every bundled package besides Pi, then Pipkin's own extensions from the source Pipkin sent", async () => {
    const packageJson = runtimeManifest(["pi-web-access", "@scope/tools"]);
    await writeRuntimeManifest(dirs, packageJson);
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({
      type: "pipkin_request",
      params: {
        cwd: dirs.home,
        sessionPath: null,
        expectedVersion: PI_VERSION,
        packageJson,
        disabledPackages: [],
        pipkinExtensions: [{ name: "pipkin-todo", source: "export default () => {};\n" }],
      },
    });
    const modules = path.join(dirs.root, "pi/node_modules");
    expect(await run.next()).toMatchObject({
      type: "ready",
      argv: [
        "--mode",
        "rpc",
        "--extension",
        path.join(modules, "pi-web-access"),
        "--extension",
        path.join(modules, "@scope/tools"),
        "--extension",
        todoExtension(dirs),
        "--append-system-prompt",
        SYSTEM_APPEND,
      ],
    });
    expect(await readFile(todoExtension(dirs), "utf8")).toBe("export default () => {};\n");
    run.socket.end();
    await run.exited;
  });

  it("leaves out the bundled packages the user turned off", async () => {
    const packageJson = runtimeManifest(["pi-web-access", "@scope/tools"]);
    await writeRuntimeManifest(dirs, packageJson);
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({
      type: "pipkin_request",
      params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson, disabledPackages: ["pi-web-access"], pipkinExtensions: [] },
    });
    expect(await run.next()).toMatchObject({
      type: "ready",
      argv: ["--mode", "rpc", "--extension", path.join(dirs.root, "pi/node_modules/@scope/tools"), "--append-system-prompt", SYSTEM_APPEND],
    });
    run.socket.end();
    await run.exited;
  });

  it("replaces a Pipkin extension an older Pipkin wrote", async () => {
    await mkdir(path.dirname(todoExtension(dirs)), { recursive: true });
    await writeFile(todoExtension(dirs), "old\n");
    await launchChat(dirs, PACKAGE_JSON, { pipkinExtensions: [{ name: "pipkin-todo", source: "new\n" }] });
    expect(await readFile(todoExtension(dirs), "utf8")).toBe("new\n");
  });

  it.each([["../escape"], ["a/b"], [""]])("refuses the Pipkin extension name %j", async (name) => {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({
      type: "pipkin_request",
      params: {
        cwd: dirs.home,
        sessionPath: null,
        expectedVersion: PI_VERSION,
        packageJson: PACKAGE_JSON,
        disabledPackages: [],
        pipkinExtensions: [{ name, source: "x" }],
      },
    });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "bad_params" });
    expect(await run.next()).toBeUndefined();
  });

  it("leaves out a bundled package the user's global settings install from npm, but not one only a project declares", async () => {
    const packageJson = runtimeManifest(["pi-web-access", "@scope/tools", "kept"]);
    await writeRuntimeManifest(dirs, packageJson);
    const globalPackages = ["npm:pi-web-access@0.38.0", { source: "npm:@scope/tools@^1", skills: [] }, "npm:kept-not"];
    await writeFile(path.join(dirs.configDir, "settings.json"), `\uFEFF${JSON.stringify({ packages: globalPackages })}`);
    // Pi may leave an untrusted project's packages unloaded, so the bundled copy must stay.
    await mkdir(path.join(dirs.home, ".pi"));
    await writeFile(path.join(dirs.home, ".pi/settings.json"), JSON.stringify({ packages: ["npm:kept"] }));
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({
      type: "ready",
      argv: ["--mode", "rpc", "--extension", path.join(dirs.root, "pi/node_modules/kept"), "--append-system-prompt", SYSTEM_APPEND],
    });
    run.socket.end();
    await run.exited;
  });

  describe("pi-web-access tool activation", () => {
    it.each([
      ["no config", null, { toolActivation: "eager" }],
      ["a config without a choice", JSON.stringify({ braveApiKey: "k" }), { braveApiKey: "k", toolActivation: "eager" }],
      ["the user's own choice", JSON.stringify({ toolActivation: "auto" }), { toolActivation: "auto" }],
    ])("makes the web tools eager given %s, keeping other settings", async (_, before, after) => {
      if (before !== null) await writeFile(path.join(dirs.configDir, "web-search.json"), before);
      const config = await webSearchConfigAfterLaunch(dirs, ["pi-web-access"]);
      expect(JSON.parse(config ?? "null")).toEqual(after);
    });

    it("leaves a config that is not a JSON object untouched", async () => {
      await writeFile(path.join(dirs.configDir, "web-search.json"), "{ broken");
      expect(await webSearchConfigAfterLaunch(dirs, ["pi-web-access"])).toBe("{ broken");
    });

    it("writes no config when pi-web-access is not bundled", async () => {
      expect(await webSearchConfigAfterLaunch(dirs, ["other"])).toBeNull();
    });

    it("writes no config when the user turned pi-web-access off", async () => {
      expect(await webSearchConfigAfterLaunch(dirs, ["pi-web-access"], ["pi-web-access"])).toBeNull();
    });
  });

  describe("steering mode", () => {
    it.each([
      ["no settings", null, { steeringMode: "all" }],
      ["settings without a choice", { defaultModel: "m" }, { defaultModel: "m", steeringMode: "all" }],
      ["the user's own choice", { steeringMode: "one-at-a-time" }, { steeringMode: "one-at-a-time" }],
    ])("delivers queued steers together given %s, keeping other settings", async (_, before, after) => {
      const settingsPath = path.join(dirs.configDir, "settings.json");
      if (before !== null) await writeFile(settingsPath, JSON.stringify(before));
      await launchChat(dirs);
      expect(JSON.parse(await readFile(settingsPath, "utf8"))).toEqual(after);
    });

    it("leaves settings Pi cannot read untouched", async () => {
      const settingsPath = path.join(dirs.configDir, "settings.json");
      await writeFile(settingsPath, "{ broken");
      await launchChat(dirs);
      expect(await readFile(settingsPath, "utf8")).toBe("{ broken");
    });
  });

  it("asks for a reinstall when the bundled packages changed but Pi's version did not", async () => {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    const packageJson = runtimeManifest(["pi-web-access"]);
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson, disabledPackages: [], pipkinExtensions: [] } });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "pi_version" });
    expect(await run.next()).toBeUndefined();
  });

  it("starts Pi with what the Termux session's startup files export, keeping Pipkin's config directory", async () => {
    await writeLogin(
      dirs,
      [
        'export SESSION_VAR="token=${PIPKIN_TOKEN:-none} root=${PIPKIN_ROOT:-none}"',
        "export PI_CODING_AGENT_DIR=/elsewhere",
        "echo startup noise",
        "echo startup warning >&2",
      ].join("\n"),
    );
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    const ready = await run.next();
    expect(ready).toMatchObject({ type: "ready" });
    expect(ready?.env).toEqual({ PI_CODING_AGENT_DIR: dirs.configDir, SESSION_VAR: "token=none root=none" });
    run.socket.end();
    await run.exited;
  });

  it("starts Pi with the host's environment, minus its own variables, when the startup files fail", async () => {
    await writeLogin(dirs, "export SESSION_VAR=partial\nexit 1");
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] } });
    const ready = await run.next();
    expect(ready).toMatchObject({ type: "ready" });
    expect(ready?.env).toEqual({ PI_CODING_AGENT_DIR: dirs.configDir });
    run.socket.end();
    await run.exited;
  });

  it("reports a missing working directory", async () => {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({
      type: "pipkin_request",
      params: { cwd: path.join(dirs.base, "nope"), sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, disabledPackages: [], pipkinExtensions: [] },
    });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "cwd_missing" });
  });
});
