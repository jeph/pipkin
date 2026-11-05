import { randomBytes } from "node:crypto";
import { mkdir, readFile, readlink, rm, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { fauxAssistantMessage, fauxToolCall, type Message, type ToolCall } from "@earendil-works/pi-ai";
import type { AgentSession, ExtensionFactory } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { isObject } from "../src/framing.js";
import { createPipkinRemote, imageMimeType } from "../src/pi-extensions/pipkin-remote/index.js";
import { sshConfigFile } from "../src/remote/paths.js";
import { quote } from "../src/remote/script.js";
import { updateHosts } from "../src/remote/hosts.js";
import { disconnect, isConnected, runScript } from "../src/remote/ssh.js";
import { alive, eventually, processTree, type RemoteFixture, signalEach, startRemoteFixture } from "./remote-harness.js";
import { shortTempDir, sshdUnavailable } from "./sshd.js";
import { fakeUi, installChannel, makeParent, messageText, removeChannel, type Script, type Turn } from "./subagents-harness.js";

const unavailable = await sshdUnavailable();
const CHANNEL = Symbol.for("pipkin.channel");
const STATE = Symbol.for("pipkin.remote");

/** A 1×1 PNG. */
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64",
);

describe("imageMimeType", () => {
  it("knows the images Pi's read tool shows, and nothing else", () => {
    expect(imageMimeType(PNG)).toBe("image/png");
    expect(imageMimeType(Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0]))).toBe("image/jpeg");
    expect(imageMimeType(Buffer.from("GIF89a......"))).toBe("image/gif");
    expect(imageMimeType(Buffer.from("RIFF\0\0\0\0WEBPVP8 "))).toBe("image/webp");
    expect(imageMimeType(Buffer.from("plain text, not an image"))).toBeNull();
    expect(imageMimeType(Buffer.alloc(0))).toBeNull();
  });
});

type ToolResult = Extract<Message, { role: "toolResult" }>;

/** Another extension's tool, which a remote chat keeps. */
const probe: ExtensionFactory = (pi) => {
  pi.registerTool({
    name: "probe",
    label: "Probe",
    description: "A tool of another extension.",
    parameters: Type.Object({}),
    execute: async () => ({ content: [{ type: "text", text: "probed" }], details: undefined }),
  });
};

describe.skipIf(unavailable !== null)(`pipkin-remote against a throwaway sshd${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let fixture: RemoteFixture;
  let session: AgentSession;
  /** What the extension told the app on the `remote` topic. */
  let sent: unknown[];
  /** Every model request, as the script saw it. */
  let turns: Turn[];
  /** What the extension listens to on the `remote` topic, as the app's messages would reach it. */
  let fromApp: ((data: unknown) => void)[];
  const environment = { PI_CODING_AGENT_DIR: process.env.PI_CODING_AGENT_DIR, PREFIX: process.env.PREFIX };

  /** Each prompt is a JSON tool call, which the model makes; then it's done. */
  const script: Script = (turn) => {
    turns.push(turn);
    if (turn.step > 0) return fauxAssistantMessage("done");
    const { tool, args } = JSON.parse(turn.latest) as { tool: string; args: ToolCall["arguments"] };
    return fauxAssistantMessage(fauxToolCall(tool, args), { stopReason: "toolUse" });
  };

  async function call(tool: string, args: ToolCall["arguments"]): Promise<ToolResult> {
    await session.prompt(JSON.stringify({ tool, args }));
    const result = session.messages.findLast((message): message is ToolResult => message.role === "toolResult");
    if (result === undefined) throw new Error("No tool result");
    return result;
  }

  /**
   * Makes every `ssh` the extension runs fail as `ssh` does with [line], a refused connection by default, until the
   * returned function undoes it.
   */
  async function breakSsh(line = "ssh: connect to host 127.0.0.1 port 22: Connection refused"): Promise<() => Promise<void>> {
    const ssh = path.join(fixture.dirs.prefix, "bin", "ssh");
    const real = path.join(fixture.dirs.prefix, "bin", "ssh.real");
    await rm(real, { force: true });
    await symlink(await readlink(ssh), real);
    await rm(ssh);
    await writeFile(ssh, `#!/bin/sh\necho ${quote(line)} >&2\nexit 255\n`, { mode: 0o755 });
    return async () => {
      await rm(ssh);
      await symlink(await readlink(real), ssh);
    };
  }

  /** Asks for a check as the app does, and waits for what the extension tells the app. */
  async function check(ms = 10_000): Promise<unknown> {
    const before = sent.length;
    for (const handler of fromApp) handler({ action: "check" });
    await eventually(() => sent.length > before, ms);
    return sent.at(-1);
  }

  beforeAll(async () => {
    fixture = await startRemoteFixture();
    process.env.PI_CODING_AGENT_DIR = fixture.dirs.configDir;
    process.env.PREFIX = fixture.dirs.prefix;
  });

  afterAll(async () => {
    for (const [key, value] of Object.entries(environment)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    await fixture.stop();
  });

  beforeEach(async () => {
    sent = [];
    turns = [];
    fromApp = [];
    const ui = fakeUi(installChannel());
    const global = globalThis as Record<symbol, unknown>;
    delete global[STATE];
    global[CHANNEL] = {
      send: (topic: string, data: unknown) => {
        if (topic === "remote") sent.push(data);
      },
      on: (topic: string, handler: (data: unknown) => void) => {
        if (topic === "remote") fromApp.push(handler);
        return () => undefined;
      },
    };
    const dir = { cwd: fixture.standIn, agentDir: fixture.dirs.configDir, sessionsDir: path.join(fixture.dirs.configDir, "sessions") };
    ({ session } = await makeParent(dir, script, {
      extensions: [probe, createPipkinRemote(fixture.standIn)],
      tools: ["read", "write", "edit", "bash", "grep", "find", "ls", "probe"],
      ui,
    }));
  });

  afterEach(() => {
    session.dispose();
    removeChannel();
    delete (globalThis as Record<symbol, unknown>)[STATE];
  });

  it("offers the remote tools without grep, find and ls, keeping other extensions' tools, and names the remote folder", async () => {
    await call("probe", {});
    const turn = turns[0];
    expect(turn?.toolNames.toSorted()).toEqual(["bash", "edit", "probe", "read", "write"]);
    expect(turn?.systemPrompt).toContain(`<cwd>\n${fixture.remoteDir}\n</cwd>`);
    expect(turn?.systemPrompt).not.toContain(fixture.standIn);
  });

  it("writes, reads and edits a 300 KiB file with awkward characters in its path, contents on standard input", async () => {
    const name = `sub/it's "$q" \`b\` * ü\nline.txt`;
    const lines = Array.from({ length: 6000 }, (_, index) => `line ${index} ${randomBytes(24).toString("hex")}`);
    const content = `${lines.join("\n")}\nMARKER\n`;
    expect(Buffer.byteLength(content)).toBeGreaterThan(300 * 1024);
    expect((await call("write", { path: name, content })).isError).toBe(false);
    const file = path.join(fixture.remoteDir, name);
    expect(await readFile(file, "utf8")).toBe(content);

    const read = await call("read", { path: name, offset: 5999, limit: 2 });
    expect(messageText(read)).toBe(`${lines[5998]}\n${lines[5999]}\n\n[2 more lines in file. Use offset=6001 to continue.]`);

    const edit = await call("edit", { path: name, edits: [{ oldText: "MARKER", newText: "EDITED" }] });
    expect(edit.isError).toBe(false);
    expect(await readFile(file, "utf8")).toBe(`${lines.join("\n")}\nEDITED\n`);
  });

  it("reads an image as an image and reports a missing file as Node would", async () => {
    await writeFile(path.join(fixture.remoteDir, "pixel.png"), PNG);
    const image = await call("read", { path: "pixel.png" });
    expect(image.content.some((block) => block.type === "image")).toBe(true);
    const missing = await call("read", { path: "nope.txt" });
    expect(missing.isError).toBe(true);
    expect(messageText(missing)).toContain(`ENOENT: no such file or directory, open '${path.join(fixture.remoteDir, "nope.txt")}'`);
  });

  it("takes ~ for the remote home folder", async () => {
    await call("write", { path: "~/notes/home.txt", content: "at home" });
    expect(await readFile(path.join(fixture.host.home ?? "", "notes/home.txt"), "utf8")).toBe("at home");
    expect(messageText(await call("read", { path: "~/notes/home.txt" }))).toBe("at home");
  });

  it("runs bash in the remote folder with stdout and stderr, failing on a non-zero exit", async () => {
    const result = await call("bash", { command: "pwd; echo oops >&2; exit 3" });
    expect(result.isError).toBe(true);
    // Pi's own format: the output as the command wrote it, then a blank line and the status.
    expect(messageText(result)).toBe(`${fixture.remoteDir}\noops\n\n\nCommand exited with code 3`);
  });

  it("ends the remote command when the user stops the run", async () => {
    const pidFile = path.join(fixture.dirs.base, "stop.pid");
    const running = session.prompt(JSON.stringify({ tool: "bash", args: { command: `sleep 617 & echo $! > ${quote(pidFile)}; wait` } }));
    await eventually(async () => (await readFile(pidFile, "utf8").catch(() => "")).endsWith("\n"));
    const pid = Number((await readFile(pidFile, "utf8")).trim());
    expect(alive(pid)).toBe(true);
    await session.abort();
    await running;
    await eventually(() => !alive(pid));
  });

  it("ends the remote command when it outlasts the tool's timeout", async () => {
    const pidFile = path.join(fixture.dirs.base, "timeout.pid");
    const result = await call("bash", { command: `sleep 618 & echo $! > ${quote(pidFile)}; wait`, timeout: 1 });
    expect(result.isError).toBe(true);
    expect(messageText(result)).toContain("Command timed out after 1 seconds");
    const pid = Number((await readFile(pidFile, "utf8")).trim());
    await eventually(() => !alive(pid));
  });

  it("reads a cut-off command's full output from the phone, where Pi saved it", async () => {
    const result = await call("bash", { command: "seq 1 50000" });
    const fullOutput = /Full output: (\S+)\]/.exec(messageText(result))?.[1];
    expect(fullOutput).toBeDefined();
    // With ssh failing, only a local read can still work.
    const repair = await breakSsh();
    try {
      const read = await call("read", { path: fullOutput ?? "", limit: 2 });
      expect(read.isError).toBe(false);
      expect(messageText(read)).toMatch(/^1\n2\n/);
      expect((await call("read", { path: path.join(fixture.remoteDir, "pixel.png") })).isError).toBe(true);
    } finally {
      await repair();
    }
  });

  it("runs the user's ! commands in the remote folder", async () => {
    const command = "pwd; echo from-remote";
    const handled = await session.extensionRunner.emitUserBash({ type: "user_bash", command, excludeFromContext: false, cwd: fixture.standIn });
    const operations = handled?.operations;
    expect(operations).toBeDefined();
    const result = await session.executeBash(command, undefined, operations === undefined ? {} : { operations });
    expect(result.output).toBe(`${fixture.remoteDir}\nfrom-remote\n`);
    expect(result.exitCode).toBe(0);
  });

  it("gives the model ssh's message, and tells the app when the connection goes and comes back", async () => {
    const repair = await breakSsh();
    let failed: ToolResult;
    try {
      failed = await call("bash", { command: "true" });
    } finally {
      await repair();
    }
    expect(failed.isError).toBe(true);
    expect(messageText(failed)).toBe("Couldn't reach testbox: ssh: connect to host 127.0.0.1 port 22: Connection refused");
    expect(sent).toEqual([
      {
        connected: false,
        error: {
          code: "remote_unreachable",
          message: "Couldn't reach testbox: ssh: connect to host 127.0.0.1 port 22: Connection refused",
          i18n: { key: "remote_unreachable", args: { host: "testbox", detail: "ssh: connect to host 127.0.0.1 port 22: Connection refused" } },
        },
      },
    ]);
    expect((await call("bash", { command: "true" })).isError).toBe(false);
    expect(sent.at(-1)).toEqual({ connected: true });
    await call("bash", { command: "true" });
    expect(sent).toHaveLength(2);
  });

  it("keeps a command's own exit status 255, which says nothing about the connection", async () => {
    const result = await call("bash", { command: "echo mine; exit 255" });
    expect(messageText(result)).toBe("mine\n\n\nCommand exited with code 255");
    expect(sent).toEqual([]);
  });

  it("tells the app a renamed password host with no connection needs signing in, on a fresh chat's first call", async () => {
    await asSignedOutPasswordHost(async () => {
      const failed = call("bash", { command: "hostname" });
      await eventually(() => sent.some((data) => isObject(data) && data.reconnect === true), 10_000);
      // The app has no saved password to sign in with.
      for (const handler of fromApp) handler({ action: "reconnected", connected: false });
      expect(messageText(await failed)).toBe("Not connected to pwhost. Connect to sign in.");
      expect(sent).toEqual([
        { reconnect: true },
        {
          connected: false,
          error: { code: "remote_not_connected", message: "Not connected to pwhost. Connect to sign in.", i18n: { key: "remote_not_connected", args: { host: "pwhost" } } },
        },
      ]);
    });
  });

  it("fails a command whose server froze as a lost connection, and tells the app when it's back", async () => {
    const remote = { paths: fixture.paths, host: fixture.host };
    const config = sshConfigFile(fixture.paths);
    const original = await readFile(config, "utf8");
    // Keepalives a second apart, so the master gives up on the frozen server in seconds rather than 45 s; a new master
    // reads them.
    await disconnect(remote);
    await writeFile(config, original.replace("ServerAliveInterval 15", "ServerAliveInterval 1"));
    let frozen: number[] = [];
    try {
      const running = call("bash", { command: "sleep 30" });
      await eventually(() => isConnected(remote));
      // As `docker pause` does: the server and the connection's sshd stop answering, and the master can't tell yet.
      // Recorded first, so cleanup resumes every process even if stopping one fails partway.
      frozen = await processTree(fixture.sshd.pid);
      signalEach(frozen, "SIGSTOP");
      const started = Date.now();
      const failed = await running;
      // Three keepalive intervals and no further wait on the server: seconds, though a loaded machine stretches them.
      expect(Date.now() - started).toBeLessThan(15_000);
      expect(failed.isError).toBe(true);
      expect(messageText(failed)).toBe("Couldn't reach testbox: Connection lost");
      expect(sent).toEqual([
        {
          connected: false,
          error: {
            code: "remote_unreachable",
            message: "Couldn't reach testbox: Connection lost",
            i18n: { key: "remote_unreachable", args: { host: "testbox", detail: "Connection lost" } },
          },
        },
      ]);
    } finally {
      signalEach(frozen, "SIGCONT");
      await writeFile(config, original);
    }
    expect((await call("bash", { command: "echo back" })).isError).toBe(false);
    expect(sent.at(-1)).toEqual({ connected: true });
  });

  it("answers each check the app asks for, connecting a key host that has no connection", async () => {
    const remote = { paths: fixture.paths, host: fixture.host };
    await disconnect(remote);
    expect(await check()).toEqual({ connected: true });
    expect(await isConnected(remote)).toBe(true);
    // Again, though nothing changed: the app may have taken its banner down meanwhile.
    expect(await check()).toEqual({ connected: true });
    expect(sent).toHaveLength(2);
  });

  it("finds a frozen server lost within seconds, with no call running, and back once it answers", async () => {
    const remote = { paths: fixture.paths, host: fixture.host };
    await call("bash", { command: "true" });
    expect(await isConnected(remote)).toBe(true);
    let frozen: number[] = [];
    try {
      frozen = await processTree(fixture.sshd.pid);
      signalEach(frozen, "SIGSTOP");
      const started = Date.now();
      expect(await check()).toEqual({
        connected: false,
        error: {
          code: "remote_unreachable",
          message: "Couldn't reach testbox: Connection timed out",
          i18n: { key: "remote_unreachable", args: { host: "testbox", detail: "Connection timed out" } },
        },
      });
      // The no-op's own timeout, far short of the keepalives' 45 s.
      expect(Date.now() - started).toBeLessThan(9_000);
      // The master is left to the keepalives: a check that came back late mustn't end a command still running.
      expect(await isConnected(remote)).toBe(true);
    } finally {
      signalEach(frozen, "SIGCONT");
    }
    expect(await check()).toEqual({ connected: true });
  });

  /**
   * Runs [body] with the fixture's host saved as a password host and no connection, in a new Pi for the chat, which
   * reads the host as it is now; then puts the host back.
   */
  async function asSignedOutPasswordHost(body: () => Promise<void>): Promise<void> {
    const remote = { paths: fixture.paths, host: fixture.host };
    const renamed = { ...fixture.host, name: "pwhost", auth: "password" as const };
    await updateHosts(fixture.paths, (hosts) => ({ hosts: hosts.map((host) => (host.id === renamed.id ? renamed : host)), result: undefined }));
    try {
      await disconnect(remote);
      session.dispose();
      const dir = { cwd: fixture.standIn, agentDir: fixture.dirs.configDir, sessionsDir: path.join(fixture.dirs.configDir, "sessions") };
      const recording = (globalThis as Record<symbol, unknown>)[CHANNEL];
      const ui = fakeUi(installChannel());
      (globalThis as Record<symbol, unknown>)[CHANNEL] = recording;
      delete (globalThis as Record<symbol, unknown>)[STATE];
      // The first Pi's extension, whose host still signs in with Pipkin's key, is gone with its session.
      fromApp = [];
      ({ session } = await makeParent(dir, script, { extensions: [createPipkinRemote(fixture.standIn)], tools: ["bash"], ui }));
      await body();
    } finally {
      await updateHosts(fixture.paths, (hosts) => ({ hosts: hosts.map((host) => (host.id === renamed.id ? fixture.host : host)), result: undefined }));
    }
  }

  it("doesn't connect a password host the user hasn't signed in", async () => {
    await asSignedOutPasswordHost(async () => {
      const repair = await breakSsh();
      try {
        expect(await check()).toEqual({
          connected: false,
          error: { code: "remote_not_connected", message: "Not connected to pwhost. Connect to sign in.", i18n: { key: "remote_not_connected", args: { host: "pwhost" } } },
        });
      } finally {
        await repair();
      }
    });
  });

  it("asks the app to sign a password host in again, and runs the call once it has", async () => {
    await asSignedOutPasswordHost(async () => {
      const result = call("bash", { command: "echo again" });
      await eventually(() => sent.some((data) => isObject(data) && data.reconnect === true), 10_000);
      // The app signed in with the saved password; here, with the key the fixture's sshd also takes.
      await updateHosts(fixture.paths, (hosts) => ({ hosts: hosts.map((host) => (host.id === fixture.host.id ? fixture.host : host)), result: undefined }));
      await runScript({ paths: fixture.paths, host: fixture.host }, "true");
      for (const handler of fromApp) handler({ action: "reconnected", connected: true });
      const output = await result;
      expect(output.isError).toBe(false);
      expect(messageText(output)).toBe("again\n");
      expect(sent.filter((data) => isObject(data) && data.reconnect === true)).toHaveLength(1);
    });
  });

  it("stops waiting for a reconnect the app never answers", async () => {
    process.env.PIPKIN_REMOTE_RECONNECT_WAIT_MS = "500";
    try {
      await asSignedOutPasswordHost(async () => {
        const output = await call("bash", { command: "echo again" });
        expect(output.isError).toBe(true);
        expect(messageText(output)).toBe("Not connected to pwhost. Connect to sign in.");
      });
    } finally {
      delete process.env.PIPKIN_REMOTE_RECONNECT_WAIT_MS;
    }
  });

  it("keeps a changed host key's warning while the key is wrong, and clears it once the key is fixed", async () => {
    const remote = { paths: fixture.paths, host: fixture.host };
    await disconnect(remote);
    const repair = await breakSsh("Host key for 127.0.0.1 has changed and you have requested strict checking.");
    try {
      await call("bash", { command: "true" });
      const changed = sent.at(-1);
      expect(changed).toMatchObject({ connected: false, error: { code: "remote_host_key_changed" } });
      // Each check connects again; a changed key fails before signing in, so it says so again without a refused sign-in.
      expect(await check()).toEqual(changed);
    } finally {
      await repair();
    }
    // Fixed outside Pipkin's Connect, as in a terminal: the next check connects.
    expect(await check()).toEqual({ connected: true });
    expect(await isConnected(remote)).toBe(true);
  });
});

describe.skipIf(unavailable !== null)("pipkin-remote outside a stand-in", () => {
  it("leaves a local folder's tools alone", async () => {
    const dir = path.join(await shortTempDir(), "project");
    await mkdir(dir, { recursive: true });
    const registered: string[] = [];
    const events: string[] = [];
    const api = {
      registerTool: (tool: { name: string }) => registered.push(tool.name),
      on: (event: string) => events.push(event),
    };
    await createPipkinRemote(dir)(api as never);
    expect(registered).toEqual([]);
    expect(events).toEqual([]);
    await rm(path.dirname(dir), { recursive: true, force: true });
  });
});
