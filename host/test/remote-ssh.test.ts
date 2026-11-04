import { randomBytes } from "node:crypto";
import { mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { HostError } from "../src/env.js";
import { accessRemote, listRemoteDir, mkdirRemote, readRemoteFile, realRemoteDir, remoteHome, RemoteFileError, statRemote, writeRemoteFile } from "../src/remote/fs.js";
import { type Host, newHostId, updateHosts } from "../src/remote/hosts.js";
import { ensureKey } from "../src/remote/key.js";
import { knownHostsFile, remotePaths, type RemotePaths, sshConfigFile } from "../src/remote/paths.js";
import { quote } from "../src/remote/script.js";
import { disconnect, isConnected, type Remote, runScript, runWatched } from "../src/remote/ssh.js";
import { freePort, linkSshTools, shortTempDir, sshdUnavailable, startSshd, type TestSshd } from "./sshd.js";

const unavailable = await sshdUnavailable();
const user = os.userInfo().username;

/** How many sign-ins sshd's [log] records. */
function signIns(log: string): number {
  return log.split("Accepted publickey").length - 1;
}

/**
 * Resolves once [check] holds; fails after [ms]. It polls, as what it waits for happens in processes sshd started,
 * which send no event this test could await.
 */
async function eventually(check: () => Promise<boolean> | boolean, ms = 5000): Promise<void> {
  const deadline = Date.now() + ms;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error("Timed out waiting");
    await delay(50);
  }
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function failure(promise: Promise<unknown>): Promise<HostError> {
  const error = await promise.then(
    () => undefined,
    (reason: unknown) => reason,
  );
  if (!(error instanceof HostError)) throw new Error(`Expected a HostError, got ${String(error)}`);
  return error;
}

describe.skipIf(unavailable !== null)(`remote runner against a throwaway sshd${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let base: string;
  let paths: RemotePaths;
  let sshd: TestSshd;
  let other: TestSshd;
  let remote: Remote;
  /** Hosts, by what they test, each with its own control socket. */
  let hosts: Record<"main" | "unknownKey" | "changedKey" | "noBash" | "password" | "closed", Host>;

  /** Saves [host] in hosts.json with a new id, and returns it. */
  async function addHost(fields: Omit<Host, "id" | "home">): Promise<Host> {
    const host: Host = { id: newHostId(), home: null, ...fields };
    await updateHosts(paths, (saved) => ({ hosts: [...saved, host], result: undefined }));
    return host;
  }

  beforeAll(async () => {
    base = await shortTempDir();
    // A home of its own, so the developer's `~/.ssh/config` stays out of the tests.
    paths = remotePaths(path.join(base, "config"), path.join(base, "prefix"), path.join(base, "home"));
    await linkSshTools(path.join(base, "prefix"));
    // A user config as macOS ones often are: an option this OpenSSH may not know, allowed by `IgnoreUnknown`, which
    // must stay the user's to set.
    await mkdir(path.join(paths.home, ".ssh"), { recursive: true });
    await writeFile(path.join(paths.home, ".ssh", "config"), "IgnoreUnknown UseKeychain\nUseKeychain yes\n");
    const key = await ensureKey(paths);
    // Without post-quantum key exchange, so OpenSSH 10.1+ would warn on every connection.
    sshd = await startSshd(path.join(base, "sshd"), { authorizedKeys: key, maxSessions: 4, kexAlgorithms: "curve25519-sha256" });
    // Without bash: `exec bash` fails in the login shell as on a server that lacks it.
    other = await startSshd(path.join(base, "other"), {
      authorizedKeys: key,
      forceCommand: `exec env PATH=/nonexistent /bin/sh -c "$SSH_ORIGINAL_COMMAND"`,
    });
    hosts = {
      main: await addHost({ name: "main", auth: "key", address: `${user}@127.0.0.1`, port: sshd.port }),
      unknownKey: await addHost({ name: "unknown", auth: "key", address: `${user}@localhost`, port: sshd.port }),
      changedKey: await addHost({ name: "changed", auth: "key", address: `${user}@localhost`, port: other.port }),
      noBash: await addHost({ name: "nobash", auth: "key", address: `${user}@127.0.0.1`, port: other.port }),
      password: await addHost({ name: "pw", auth: "password", address: `${user}@127.0.0.1`, port: sshd.port }),
      closed: await addHost({ name: "closed", auth: "key", address: `${user}@127.0.0.1`, port: await freePort() }),
    };
    await writeFile(
      knownHostsFile(paths),
      [`[127.0.0.1]:${sshd.port} ${sshd.hostKey}`, `[127.0.0.1]:${other.port} ${other.hostKey}`, `[localhost]:${other.port} ${sshd.hostKey}`, ""].join("\n"),
    );
    remote = { paths, host: hosts.main };
  });

  afterAll(async () => {
    for (const host of Object.values(hosts)) await disconnect({ paths, host });
    await sshd.stop();
    await other.stop();
    await rm(base, { recursive: true, force: true });
  });

  it("runs a script through a non-bash login shell with its quoting intact and nothing of ssh's own in stderr", async () => {
    const weird = `it's "$HOME" \`x\` * \\ ü\nsecond line`;
    const result = await runScript(remote, `printf '%s|' ${quote(weird)}; echo err >&2; exit 3`);
    expect(result.code).toBe(3);
    expect(result.stdout.toString()).toBe(`${weird}|`);
    expect(result.stderr).toBe("err\n");
    expect(await readFile(sshConfigFile(paths), "utf8")).toContain(`  Include ${path.join(paths.home, ".ssh", "config")}\n`);
  });

  it("keeps the master connection after a call, and ends it on disconnect", async () => {
    await runScript(remote, "true");
    expect(await isConnected(remote)).toBe(true);
    await disconnect(remote);
    expect(await isConnected(remote)).toBe(false);
  });

  it("passes on a 255 from the command itself, even with ssh's own words in its stderr", async () => {
    const result = await runScript(remote, "ssh -F /dev/null -o BatchMode=yes -p 1 127.0.0.1 true");
    expect(result.code).toBe(255);
    expect(result.stderr).toContain("Connection refused");
  });

  it("writes and reads back a file larger than one command-line argument", async () => {
    const file = path.join(base, "big.bin");
    const data = randomBytes(300 * 1024);
    await writeRemoteFile(remote, file, data);
    expect((await readFile(file)).equals(data)).toBe(true);
    expect((await readRemoteFile(remote, file)).equals(data)).toBe(true);
  });

  it("lists a folder with hidden entries, links followed and odd names, leaving dangling links out", async () => {
    const dir = path.join(base, "listing");
    await mkdir(path.join(dir, "sub dir"), { recursive: true });
    await writeFile(path.join(dir, "a file"), "12345");
    await writeFile(path.join(dir, ".hidden"), "");
    await writeFile(path.join(dir, "new\nline"), "x");
    await symlink(path.join(dir, "sub dir"), path.join(dir, "link"));
    await symlink(path.join(dir, "gone"), path.join(dir, "dangling"));
    const entries = await listRemoteDir(remote, dir);
    const byName = Object.fromEntries(entries.map((entry) => [entry.name, entry]));
    expect(Object.keys(byName).toSorted()).toEqual([".hidden", "a file", "link", "new\nline", "sub dir"]);
    expect(byName["a file"]).toMatchObject({ kind: "file", size: 5, symlink: false, target: null });
    expect(byName.link).toMatchObject({ kind: "directory", symlink: true, target: path.join(dir, "sub dir") });
    expect(byName["sub dir"]?.mtimeMs).toBeGreaterThan(Date.now() - 60_000);
    expect(await listRemoteDir(remote, path.join(dir, "sub dir"))).toEqual([]);
  });

  it("stats, makes folders, resolves links and reports missing paths like Node", async () => {
    const dir = path.join(base, "ops");
    await mkdirRemote(remote, path.join(dir, "a/b"));
    expect(await statRemote(remote, path.join(dir, "a/b"))).toMatchObject({ kind: "directory" });
    expect(await statRemote(remote, path.join(dir, "missing"))).toBeNull();
    await symlink(path.join(dir, "a"), path.join(dir, "via"));
    expect(await realRemoteDir(remote, path.join(dir, "via/b"))).toBe(path.join(dir, "a/b"));
    expect(await remoteHome(remote)).toBe(os.userInfo().homedir);

    const missing = await readRemoteFile(remote, path.join(dir, "missing")).catch((error: unknown) => error);
    expect(missing).toBeInstanceOf(RemoteFileError);
    expect(missing).toMatchObject({ code: "ENOENT", message: `ENOENT: no such file or directory, open '${path.join(dir, "missing")}'` });
    await expect(readRemoteFile(remote, dir)).rejects.toMatchObject({ code: "EISDIR" });
    await expect(accessRemote(remote, path.join(dir, "missing"))).rejects.toMatchObject({ code: "ENOENT" });
    await expect(writeRemoteFile(remote, path.join(dir, "none/x"), "x")).rejects.toMatchObject({ code: "ENOENT" });
    await expect(listRemoteDir(remote, path.join(dir, "missing"))).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("stops a watched command's whole process group on the remote when the run is aborted", async () => {
    const pidFile = path.join(base, "abort.pid");
    const controller = new AbortController();
    const running = runWatched(remote, `sleep 613 & echo $! > ${quote(pidFile)}; sleep 614`, base, { signal: controller.signal });
    await eventually(async () => (await readFile(pidFile, "utf8").catch(() => "")).endsWith("\n"));
    const pid = Number((await readFile(pidFile, "utf8")).trim());
    expect(alive(pid)).toBe(true);
    controller.abort(new Error("stopped"));
    await expect(running).rejects.toThrow("stopped");
    await eventually(() => !alive(pid));
  });

  it("ends a watched command on the remote when it outlasts its timeout", async () => {
    const pidFile = path.join(base, "timeout.pid");
    const running = runWatched(remote, `echo $$ > ${quote(pidFile)}; sleep 615; true`, base, { timeoutMs: 1500 });
    await expect(running).rejects.toMatchObject({ name: "TimeoutError" });
    const pid = Number((await readFile(pidFile, "utf8")).trim());
    await eventually(() => !alive(pid));
  });

  it("returns a watched command's status and output once it ends, with standard input still open", async () => {
    const result = await runWatched(remote, "pwd; echo e >&2; exit 4", path.join(base, "listing"));
    expect(result.code).toBe(4);
    expect(result.stdout.toString()).toBe(`${path.join(base, "listing")}\n`);
    // Only the command's own: none of the job notices `set -m` makes bash print.
    expect(result.stderr).toBe("e\n");
  });

  it("says why a watched command couldn't start in a missing folder", async () => {
    const result = await runWatched(remote, "true", path.join(base, "missing"));
    expect(result.code).toBe(1);
    expect(result.stderr).toContain("missing");
  });

  it("queues sessions past the per-host cap instead of opening connections of their own", async () => {
    await runScript(remote, "true");
    const before = signIns(sshd.log());
    const results = await Promise.all(Array.from({ length: 12 }, (_, index) => runScript(remote, `sleep 0.2; echo ${index}`)));
    expect(results.map((result) => result.stdout.toString().trim())).toEqual(Array.from({ length: 12 }, (_, index) => String(index)));
    // The server allows 4 sessions; a 5th at once would have fallen back to a connection, and a sign-in, of its own.
    expect(signIns(sshd.log())).toBe(before);
  });

  it("tells an unknown host key from a changed one", async () => {
    expect((await failure(runScript({ paths, host: hosts.unknownKey }, "true"))).code).toBe("remote_host_key_unknown");
    const changed = await failure(runScript({ paths, host: hosts.changedKey }, "true"));
    expect(changed).toMatchObject({ code: "remote_host_key_changed", i18n: { key: "remote_host_key_changed", args: { host: "changed" } } });
  });

  it("asks for a connect when a password host has no live connection", async () => {
    expect((await failure(runScript({ paths, host: hosts.password }, "true"))).code).toBe("remote_not_connected");
  });

  it("reports a refused connection with ssh's own line", async () => {
    const error = await failure(runScript({ paths, host: hosts.closed }, "true"));
    expect(error).toMatchObject({ code: "remote_unreachable", i18n: { args: { detail: expect.stringContaining("Connection refused") } } });
  });

  it("reports a remote without bash", async () => {
    expect((await failure(runScript({ paths, host: hosts.noBash }, "true"))).code).toBe("remote_bash_missing");
  });

  it("reports a Termux without OpenSSH", async () => {
    const bare = { ...paths, bin: path.join(base, "empty-bin") };
    expect((await failure(runScript({ paths: bare, host: hosts.main }, "true"))).code).toBe("ssh_missing");
  });
});
