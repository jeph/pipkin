import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { HostError } from "../src/env.js";
import { describePrompt } from "../src/remote/askpass.js";
import { parseProbe } from "../src/remote/connect.js";
import { classifySshFailure, type FailureContext, isBashMissing } from "../src/remote/errors.js";
import { type Host, loadHosts, parseAddress, sshConfig, updateHosts, validateHost } from "../src/remote/hosts.js";
import { remotePaths } from "../src/remote/paths.js";
import { quote, remoteScript, watched } from "../src/remote/script.js";
import { acquireSession, MAX_SESSIONS_PER_HOST } from "../src/remote/ssh.js";
import { createStandIn, listStandIns, readStandIn, standInFor } from "../src/remote/stand-ins.js";

const run = promisify(execFile);

function host(fields: Partial<Host>): Host {
  return { id: "0123456789ab", name: "box", auth: "key", address: "me@box", port: 22, home: null, ...fields };
}

/** The `i18n` of the `remote_host_invalid` that [draft] fails with. */
function invalidity(draft: object, hosts: Host[] = []): unknown {
  try {
    validateHost(draft, hosts);
  } catch (error) {
    if (error instanceof HostError && error.code === "remote_host_invalid") return error.i18n;
    throw error;
  }
  throw new Error("Accepted");
}

describe("host validation", () => {
  const draft = { name: "devbox", auth: "key", address: "me@devbox.lan", port: 22 };

  it.each(["", " ", "me@", "@host", "host:22", "me@host:22", "my host", "-oProxyCommand=x", "::1", "me@[host]"])(
    "refuses the address %j",
    (address) => {
      expect(invalidity({ ...draft, address })).toMatchObject({ args: { field: "address" } });
    },
  );

  it.each(["host", "me@host", "me@my-host.lan", "[::1]", "me@[fe80::1%eth0]", "10.0.2.2", "me@domain@host"])("accepts the address %j", (address) => {
    expect(validateHost({ ...draft, address }, [])).toMatchObject({ address });
  });

  it("splits a user from a host at the last @ and strips IPv6 brackets", () => {
    expect(parseAddress("me@[::1]")).toEqual({ user: "me", host: "::1" });
    expect(parseAddress("me@domain@host")).toEqual({ user: "me@domain", host: "host" });
    expect(parseAddress("host")).toEqual({ user: null, host: "host" });
  });

  it.each([0, 65_536, "abc", 22.5, null])("refuses the port %j", (port) => {
    expect(invalidity({ ...draft, port })).toEqual({ key: "remote_port_invalid", args: { field: "port", port: String(port) } });
  });

  it("refuses a name another host has, ignoring case, but not the host's own", () => {
    const saved = [host({ id: "aaaaaaaaaaaa", name: "DevBox" })];
    expect(invalidity(draft, saved)).toEqual({ key: "remote_name_taken", args: { field: "name", name: "devbox" } });
    expect(validateHost({ ...draft, id: "aaaaaaaaaaaa" }, saved).name).toBe("devbox");
    expect(invalidity({ ...draft, name: "  " })).toMatchObject({ key: "remote_name_empty", args: { field: "name" } });
  });

  it("takes a config host's alias without a port, and refuses a pattern or option", () => {
    expect(validateHost({ name: "b", auth: "config", address: "box", port: 22 }, [])).toEqual({
      id: null,
      name: "b",
      auth: "config",
      address: "box",
      port: null,
    });
    expect(invalidity({ name: "b", auth: "config", address: "*.lan" })).toMatchObject({ key: "remote_alias_invalid" });
    expect(invalidity({ name: "b", auth: "config", address: "-F/x" })).toMatchObject({ key: "remote_alias_invalid" });
  });
});

describe("hosts store", () => {
  let base: string;

  beforeEach(async () => {
    base = await mkdtemp(path.join(os.tmpdir(), "pk-hosts-"));
  });

  afterEach(async () => {
    await rm(base, { recursive: true, force: true });
  });

  it("generates the config with a block per key or password host, and none for a config host", () => {
    const paths = remotePaths("/data/home/.pipkin/pi", "/data/usr", "/data/home");
    const hosts = [
      host({ id: "aaaaaaaaaaaa", address: "me@devbox.lan", port: 2222 }),
      host({ id: "bbbbbbbbbbbb", name: "pw", auth: "password", address: "[::1]", port: 22 }),
      host({ id: "cccccccccccc", name: "cfg", auth: "config", address: "box", port: null }),
    ];
    const config = sshConfig(paths, hosts, true);
    expect(config).toBe(`# Written by Pipkin from hosts.json; changes here are overwritten.
Host *
  ServerAliveInterval 15
  ServerAliveCountMax 2
  ConnectTimeout 15
  UserKnownHostsFile /data/home/.pipkin/ssh/known_hosts /data/home/.ssh/known_hosts
  HashKnownHosts no
  WarnWeakCrypto no

Host pipkin-aaaaaaaaaaaa
  HostName devbox.lan
  User me
  Port 2222
  IdentityFile /data/home/.pipkin/ssh/id_ed25519
  IdentitiesOnly yes
  PreferredAuthentications publickey

Host pipkin-bbbbbbbbbbbb
  HostName ::1
  Port 22
  IdentityFile /data/home/.pipkin/ssh/id_ed25519
  IdentitiesOnly yes
  PreferredAuthentications password,keyboard-interactive

Match all
  Include /data/home/.ssh/config
`);
    // An OpenSSH older than 10.1 would refuse the whole file over an option it doesn't know.
    expect(sshConfig(paths, hosts, false)).not.toContain("WarnWeakCrypto");
    expect(sshConfig(remotePaths("/data/home/.pipkin/pi", "/data/usr", "/my home"), [], false)).toContain('Include "/my home/.ssh/config"');
  });

  it("keeps both of two saves made at once, writes private files, and drops hand-broken entries on load", async () => {
    const paths = remotePaths(path.join(base, "pi"), base, base);
    await Promise.all([
      updateHosts(paths, (hosts) => ({ hosts: [...hosts, host({ id: "aaaaaaaaaaaa", name: "a" })], result: undefined })),
      updateHosts(paths, (hosts) => ({ hosts: [...hosts, host({ id: "bbbbbbbbbbbb", name: "b" })], result: undefined })),
    ]);
    expect((await loadHosts(paths)).map((saved) => saved.name)).toEqual(["a", "b"]);
    expect((await stat(path.join(paths.ssh, "hosts.json"))).mode & 0o777).toBe(0o600);
    expect((await stat(paths.ssh)).mode & 0o777).toBe(0o700);
    expect(await readFile(path.join(paths.ssh, "config"), "utf8")).toContain("Host pipkin-bbbbbbbbbbbb");

    const file = path.join(paths.ssh, "hosts.json");
    const good = await loadHosts(paths);
    await writeFile(file, JSON.stringify({ version: 1, hosts: [...good, { id: "zz", name: "bad" }, { ...host({}), port: 0 }] }));
    expect((await loadHosts(paths)).map((loaded) => loaded.name)).toEqual(["a", "b"]);
  });
});

describe("stand-ins", () => {
  let remotes: string;

  beforeEach(async () => {
    remotes = path.join(await mkdtemp(path.join(os.tmpdir(), "pk-remotes-")), "remotes");
  });

  afterEach(async () => {
    await rm(path.dirname(remotes), { recursive: true, force: true });
  });

  it("names a folder by the first 16 hex digits of the remote path's SHA-256, the same for the same folder", () => {
    const hash = createHash("sha256").update("/home/me/src/api").digest("hex").slice(0, 16);
    expect(standInFor(remotes, "0123456789ab", "/home/me/src/api")).toBe(path.join(remotes, "0123456789ab", hash));
    expect(standInFor(remotes, "0123456789ab", "/home/me/src/api/")).toBe(standInFor(remotes, "0123456789ab", "/home/me//src/api"));
    expect(() => standInFor(remotes, "0123456789ab", "src/api")).toThrow(HostError);
  });

  it("reads back what a stand-in stands for, and lists every one", async () => {
    const cwd = await createStandIn(remotes, "0123456789ab", "/srv/app");
    expect(await createStandIn(remotes, "0123456789ab", "/srv/app")).toBe(cwd);
    expect(await readStandIn(remotes, cwd)).toEqual({ hostId: "0123456789ab", path: "/srv/app" });
    const root = await createStandIn(remotes, "ba9876543210", "/");
    expect((await listStandIns(remotes)).toSorted((a, b) => a.path.localeCompare(b.path))).toEqual([
      { cwd: root, hostId: "ba9876543210", path: "/" },
      { cwd, hostId: "0123456789ab", path: "/srv/app" },
    ]);
  });

  it("never takes a local folder holding a remote.json for a stand-in", async () => {
    const local = path.join(path.dirname(remotes), "project");
    await mkdir(local);
    await writeFile(path.join(local, "remote.json"), JSON.stringify({ version: 1, hostId: "0123456789ab", path: "/srv/app" }));
    expect(await readStandIn(remotes, local)).toBeNull();
    // In place, but its manifest names another folder than its hash.
    const cwd = await createStandIn(remotes, "0123456789ab", "/srv/app");
    await writeFile(path.join(cwd, "remote.json"), JSON.stringify({ version: 1, hostId: "0123456789ab", path: "/srv/other" }));
    expect(await readStandIn(remotes, cwd)).toBeNull();
  });
});

/** The login shells this machine has, which run the wrapper on a remote. */
async function shells(): Promise<string[]> {
  const found = await Promise.all(
    ["sh", "bash", "zsh", "fish", "dash"].map((name) => run("/bin/sh", ["-c", `command -v ${name}`]).then(({ stdout }) => stdout.trim(), () => null)),
  );
  return found.filter((shell) => shell !== null);
}

describe("command wrapper", () => {
  const nasty = `it's "$HOME" \`whoami\` $(id) * ~ \\ ü\n\ttab`;

  it("delivers a script and its quoted words intact through every login shell here", async () => {
    const script = `printf '%s|' ${quote(nasty)} "$0"; printf '%s' "\${BASH_VERSION:+bash}"`;
    for (const shell of await shells()) {
      const { stdout } = await run(shell, ["-c", remoteScript(script)]);
      expect({ shell, stdout }).toEqual({ shell, stdout: `${nasty}|bash|bash` });
    }
  });

  it("refuses a word with a NUL, which a shell would cut short", () => {
    expect(() => quote("a\0b")).toThrow(HostError);
  });

  it("runs a watched command in its folder and ends with its status and its own stderr, input still open", async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), "pk it's "));
    try {
      // The pause lets the watcher start, so a watcher still holding an output would keep `close` from coming.
      const child = execFile("bash", ["-c", remoteScript(watched("pwd; echo oops >&2; sleep 0.2; exit 7", dir))]);
      let stdout = "";
      let stderr = "";
      child.stdout?.on("data", (chunk: string) => (stdout += chunk));
      child.stderr?.on("data", (chunk: string) => (stderr += chunk));
      const { promise, resolve } = Promise.withResolvers<number | null>();
      child.on("close", resolve);
      expect(await promise).toBe(7);
      expect(stdout).toBe(`${dir}\n`);
      expect(stderr).toBe("oops\n");
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});

describe("ssh failure classification", () => {
  const batch: FailureContext = { name: "devbox", auth: "key", interactive: false };
  const pqWarning =
    "** WARNING: connection is not using a post-quantum key exchange algorithm.\n** This session may be vulnerable to \"store now, decrypt later\" attacks.\n";

  it.each([
    ["No ED25519 host key is known for [127.0.0.1]:42222 and you have requested strict checking.\nHost key verification failed.\n", "remote_host_key_unknown"],
    [
      "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\nIT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\nThe fingerprint for the ED25519 key sent by the remote host is\nSHA256:8zo3KGaAUG/xVChQGSB0LEPUQa0w1gti6htTrkbqQ88.\nOffending ED25519 key in /tmp/kh:1\nHost key for [127.0.0.1]:42222 has changed and you have requested strict checking.\nHost key verification failed.\n",
      "remote_host_key_changed",
    ],
    ["jeph@127.0.0.1: Permission denied (publickey,password,keyboard-interactive).\n", "remote_auth_failed"],
    ["Received disconnect from 10.0.2.2 port 2222:2: Too many authentication failures\nDisconnected from 10.0.2.2 port 2222\n", "remote_auth_failed"],
    ["ssh: connect to host 127.0.0.1 port 42222: Connection refused\n", "remote_unreachable"],
    ["ssh: Could not resolve hostname nosuchhost.invalid: nodename nor servname provided, or not known\n", "remote_unreachable"],
    ["ssh: Could not resolve hostname box: Name or service not known\n", "remote_unreachable"],
    ["ssh: connect to host 10.0.2.2 port 2222: Connection timed out\n", "remote_unreachable"],
    ["Connection timed out during banner exchange\nConnection to 10.0.2.2 port 2222 timed out\n", "remote_unreachable"],
    ["kex_exchange_identification: read: Connection reset by peer\nConnection reset by 10.0.2.2 port 2222\n", "remote_unreachable"],
    [`${pqWarning}Timeout, server 10.0.2.2 not responding.\n`, "remote_unreachable"],
    ["client_loop: send disconnect: Broken pipe\n", "remote_unreachable"],
    ["Bad owner or permissions on /data/home/.ssh/config\n", "remote_failed"],
    ["/data/home/.ssh/config line 3: Bad configuration option: foo\n", "remote_failed"],
  ])("recognises %j", (stderr, code) => {
    expect(classifySshFailure(stderr, batch)?.code).toBe(code);
  });

  it("asks for a connect when batch mode can't sign in to a password or config host", () => {
    const denied = "pipkin@10.0.2.2: Permission denied (password,keyboard-interactive).\n";
    expect(classifySshFailure(denied, { ...batch, auth: "password" })?.code).toBe("remote_not_connected");
    expect(classifySshFailure(denied, { ...batch, auth: "config" })?.code).toBe("remote_not_connected");
    expect(classifySshFailure(denied, { ...batch, auth: "password", interactive: true })?.code).toBe("remote_auth_failed");
  });

  it("gives ssh's own line as the detail, past warnings", () => {
    const failure = classifySshFailure(`Warning: Permanently added '[h]:22' (ED25519) to the list of known hosts.\n${pqWarning}ssh: connect to host h port 22: No route to host\n`, batch);
    expect(failure?.i18n).toEqual({ key: "remote_unreachable", args: { host: "devbox", detail: "ssh: connect to host h port 22: No route to host" } });
  });

  it("leaves a 255 whose stderr ssh didn't write to the command", () => {
    expect(classifySshFailure("fatal: could not read from remote repository\n", batch)).toBeUndefined();
    expect(classifySshFailure("", batch)).toBeUndefined();
  });

  it.each([
    ["sh: 1: exec: bash: not found\n", true],
    ["sh: line 0: exec: bash: not found\n", true],
    ["zsh:1: command not found: bash\n", true],
    ["fish: Unknown command: bash\n", true],
    ["sh: bash: not found\n", true],
    ["bash: line 1: foo: command not found\n", false],
  ])("tells a missing bash from a missing command in %j", (stderr, missing) => {
    expect(isBashMissing(127, stderr)).toBe(missing);
    expect(isBashMissing(1, stderr)).toBe(false);
  });
});

describe("askpass prompts", () => {
  it.each([
    ["OpenSSH 9", "ED25519 key fingerprint is SHA256:8zo3KGaAUG/xVChQGSB0LEPUQa0w1gti6htTrkbqQ88."],
    ["OpenSSH 10", "ED25519 key fingerprint is: SHA256:8zo3KGaAUG/xVChQGSB0LEPUQa0w1gti6htTrkbqQ88"],
  ])("parses the host key's type and fingerprint out of %s's question", (_, line) => {
    const text = `The authenticity of host '[10.0.2.2]:2222 ([10.0.2.2]:2222)' can't be established.\n${line}\nThis key is not known by any other names.\nAre you sure you want to continue connecting (yes/no/[fingerprint])? `;
    expect(describePrompt(text)).toEqual({ kind: "host_key", text, keyType: "ED25519", fingerprint: "SHA256:8zo3KGaAUG/xVChQGSB0LEPUQa0w1gti6htTrkbqQ88" });
  });

  it.each([
    ["pipkin@10.0.2.2's password: ", "password"],
    ["(pipkin@10.0.2.2) Password: ", "password"],
    ["Enter passphrase for key '/data/home/.ssh/id_ed25519': ", "passphrase"],
    ["(pipkin@10.0.2.2) Verification code: ", "other"],
    ["Allow shared connection to box? ", "other"],
    ["The host's key is new to us.\nAre you sure you want to continue connecting (yes/no)? ", "host_key"],
  ])("calls %j a %s prompt", (text, kind) => {
    expect(describePrompt(text)).toEqual({ kind, text });
  });

  it("reads the probe's home and tools by their names", () => {
    expect(parseProbe("/home/me\n/usr/bin/bash\n/usr/local/bin/rg\n")).toEqual({ home: "/home/me", tools: { bash: true, git: false, rg: true } });
    expect(parseProbe("Welcome!\n")).toBeNull();
  });
});

describe("per-host session cap", () => {
  it("holds sessions past the cap until one ends, and lets an aborted waiter go", async () => {
    const releases = await Promise.all(Array.from({ length: MAX_SESSIONS_PER_HOST }, () => acquireSession("cap-test")));
    let fifth = false;
    const waiting = acquireSession("cap-test").then((release) => {
      fifth = true;
      return release;
    });
    const controller = new AbortController();
    const aborted = acquireSession("cap-test", controller.signal);
    controller.abort(new Error("gone"));
    await expect(aborted).rejects.toThrow("gone");
    // Another host has slots of its own.
    (await acquireSession("other-host"))();
    expect(fifth).toBe(false);
    releases[0]?.();
    const release = await waiting;
    expect(fifth).toBe(true);
    for (const held of [release, ...releases.slice(1)]) held();
  });
});
