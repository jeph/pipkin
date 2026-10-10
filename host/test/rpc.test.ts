import { access } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { encodeLine } from "../src/framing.js";
import { systemAppend } from "../src/prompt.js";
import { type Dirs, installFakePi, makeDirs, PI_VERSION, removeDirs, startHost } from "./harness.js";

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
      encodeLine({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: "/s.jsonl", expectedVersion: PI_VERSION } }) +
        encodeLine(first),
    );
    expect(await run.next()).toEqual({
      type: "ready",
      argv: ["--mode", "rpc", "--session", "/s.jsonl", "--append-system-prompt", systemAppend(dirs.configDir)],
      cwd: dirs.home,
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
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION } });
    expect(await run.next()).toMatchObject({ type: "ready" });
    run.socket.destroy();
    // The host exits only after Pi exited, and fake-pi writes the marker on stdin EOF.
    expect(await run.exited).toBe(0);
    await expect(access(marker)).resolves.toBeUndefined();
  });

  it("refuses a Pi version other than the expected one", async () => {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: "9.9.9" } });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "pi_version" });
    expect(await run.next()).toBeUndefined();
  });

  it("reports a missing working directory", async () => {
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: path.join(dirs.base, "nope"), sessionPath: null, expectedVersion: PI_VERSION } });
    expect(await run.next()).toMatchObject({ type: "pipkin_result", ok: false, code: "cwd_missing" });
  });
});
