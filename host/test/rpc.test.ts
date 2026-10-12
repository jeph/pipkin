import { access } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { encodeLine } from "../src/framing.js";
import { systemAppend } from "../src/prompt.js";
import { type Dirs, type HostRun, installFakePi, makeDirs, PI_VERSION, removeDirs, startHost } from "./harness.js";

const MiB = 1024 * 1024;

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

  // Android can freeze Pipkin while the screen is off; Pi must keep working rather than wait for it to read.
  it("keeps Pi writing while the app is not reading", async () => {
    const done = path.join(dirs.base, "flood-done");
    const { run } = await startHost("rpc", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION } });
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
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION } });
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
    run.send({ type: "pipkin_request", params: { cwd: dirs.home, sessionPath: null, expectedVersion: PI_VERSION } });
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
