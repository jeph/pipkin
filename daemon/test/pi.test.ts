import fsp from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { processTable } from "../src/owned.js";
import { PI_ARGS, PI_PACKAGE, PI_VERSION } from "../src/pi.js";
import { Daemon, type DaemonConfig } from "../src/server.js";
import { TestClient, testCredentials, type TestCredentials } from "./client.js";

/**
 * Exercises the real Pi 0.85.1 RPC entry (devDependency) without a provider: its `bash`
 * command spawns tool children detached into their own process groups, which is exactly
 * what the daemon's descendant sweep must cover, including after a forced Pi exit.
 */
const INSTALL_ID = "11111111-2222-4333-8444-555555555555";
const RPC_ENTRY = path.join(import.meta.dirname, "../node_modules", PI_PACKAGE, "dist/bundle/rpc-entry.js");
const TRACK_MS = 50;

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/** Starts a generation running `sleep` through Pi's bash tool; returns Pi's pid and the tool child's pid. */
async function startSleeping(client: TestClient, root: string, generation: number): Promise<{ pi: number; child: number }> {
  const pidFile = path.join(root, `child-${generation}.pid`);
  const started = await client.result("agent.start", { generation, workspace: "w" });
  await client.result("agent.send", {
    generation,
    record: { type: "bash", id: "b1", command: `echo $$ > "${pidFile}"; sleep 300` },
  });
  let child = 0;
  while (child === 0) {
    child = Number(await fsp.readFile(pidFile, "utf8").catch(() => "0"));
    if (child === 0) await delay(20);
  }
  return { pi: started.pid as number, child };
}

describe("real Pi", () => {
  let root: string;
  let credentials: TestCredentials;
  let daemon: Daemon;

  beforeEach(async () => {
    root = await fsp.mkdtemp(path.join(os.tmpdir(), "pipkin-pi-"));
    credentials = await testCredentials();
    const manifest = path.join(root, "runtime/pi/node_modules", PI_PACKAGE);
    await fsp.mkdir(manifest, { recursive: true });
    await fsp.writeFile(path.join(manifest, "package.json"), JSON.stringify({ version: PI_VERSION }));
    const config: DaemonConfig = {
      host: "127.0.0.1",
      port: 0,
      installId: INSTALL_ID,
      credentials,
      root,
      prefix: "/usr",
      shell: "/bin/bash",
      uid: process.getuid?.() ?? -1,
      environment: {
        PATH: "/usr/bin:/bin",
        HOME: path.join(root, "home"),
        TMPDIR: path.join(root, "tmp"),
        SHELL: "/bin/bash",
        TERM: "dumb",
        NO_COLOR: "1",
      },
      npm: "/bin/false",
      pi: { command: process.execPath, args: [RPC_ENTRY, ...PI_ARGS] },
      piVersion: PI_VERSION,
      idleExitMs: 60_000,
      idlePollMs: 60_000,
      trackMs: TRACK_MS,
    };
    daemon = new Daemon(config);
    await daemon.start();
  });

  afterEach(async () => {
    await daemon.shutdown(0);
    await fsp.rm(root, { recursive: true, force: true });
  });

  it("stops a bash tool child that Pi detached into its own process group", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    const { pi, child } = await startSleeping(client, root, 1);
    const entry = processTable().get(child);
    expect(entry?.pgid).not.toBe(pi);
    expect(alive(child)).toBe(true);

    await client.result("agent.close", { generation: 1 });
    expect(alive(pi)).toBe(false);
    expect(alive(child)).toBe(false);
    const exit = await client.nextEvent("exit");
    expect(exit).toMatchObject({ generation: 1 });
  });

  it("sweeps tracked children after Pi is killed without a chance to clean up", async () => {
    const client = await TestClient.connect(daemon.port, credentials);
    const { pi, child } = await startSleeping(client, root, 2);
    // Real timer: the tracker must have observed the child once before Pi disappears.
    await delay(TRACK_MS * 3);
    process.kill(pi, "SIGKILL");
    const exit = await client.nextEvent("exit");
    expect(exit).toMatchObject({ generation: 2, signal: "SIGKILL" });
    expect(alive(child)).toBe(false);
  });
});
