import { execFile } from "node:child_process";
import { chmod, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { isObject, type JsonObject } from "../src/framing.js";
import { type Dirs, runMode, runOp, startHost } from "./harness.js";
import { linkSshTools, shortDirs, sshdUnavailable, startSshd, type TestSshd } from "./sshd.js";

const run = promisify(execFile);
const unavailable = await sshdUnavailable();
const user = os.userInfo().username;

/** Runs `remote_connect` for [hostId], answering each prompt with [answer]; returns the prompts and the result. */
async function connect(dirs: Dirs, hostId: string, answer: (prompt: JsonObject) => string | null): Promise<{ prompts: JsonObject[]; result: JsonObject }> {
  const { run: host } = await startHost("remote_connect", dirs);
  await host.next();
  host.send({ type: "pipkin_request", params: { hostId } });
  const prompts: JsonObject[] = [];
  for (;;) {
    const message = await host.next();
    if (message === undefined) throw new Error("remote_connect closed without a result");
    if (message.type === "pipkin_remote_prompt") {
      prompts.push(message);
      host.send({ type: "pipkin_remote_answer", id: message.id, answer: answer(message) });
    }
    if (message.type === "pipkin_result") {
      await host.exited;
      return { prompts, result: message };
    }
  }
}

/** The string at [keys] inside [value], failing the test when it isn't one. */
function stringAt(value: unknown, ...keys: string[]): string {
  const found = keys.reduce<unknown>((inner, key) => (isObject(inner) ? inner[key] : undefined), value);
  if (typeof found !== "string") throw new Error(`No string at ${keys.join(".")} in ${JSON.stringify(value)}`);
  return found;
}

/** Stands in for Termux's `pkg` in [dirs], printing [output] and exiting with [code]. */
async function fakePkg(dirs: Dirs, output: string, code: number): Promise<void> {
  const pkg = path.join(dirs.prefix, "bin", "pkg");
  await writeFile(pkg, `#!/bin/sh\necho "pkg $*"\ncat >&2 <<'EOF'\n${output}\nEOF\nexit ${code}\n`);
  await chmod(pkg, 0o755);
}

async function saveHost(dirs: Dirs, host: JsonObject): Promise<string> {
  return stringAt(await runOp("remote_host_save", dirs, { host }), "data", "host", "id");
}

describe("remote host operations", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await shortDirs();
  });

  afterEach(async () => {
    await rm(dirs.base, { recursive: true, force: true });
  });

  it("saves hosts, keeps stand-ins of removed ones, and reports a missing OpenSSH", async () => {
    expect(await runOp("remote_hosts", dirs, {})).toEqual({
      type: "pipkin_result",
      ok: true,
      data: { sshInstalled: false, publicKey: null, hosts: [], workspaces: [] },
    });
    const id = await saveHost(dirs, { name: "devbox", auth: "key", address: "me@devbox.lan", port: 22 });
    const workspace = await runOp("remote_workspace", dirs, { hostId: id, path: "/home/me/src/api" });
    const cwd = stringAt(workspace, "data", "cwd");
    expect(path.dirname(path.dirname(cwd))).toBe(path.join(dirs.base, "pipkin", "remotes"));
    expect(await runOp("remote_host_remove", dirs, { id })).toMatchObject({ ok: true });
    expect(await runOp("remote_hosts", dirs, {})).toMatchObject({ data: { hosts: [], workspaces: [{ cwd, hostId: id, path: "/home/me/src/api" }] } });
    expect(await runOp("remote_workspace", dirs, { hostId: id, path: "/x" })).toMatchObject({ ok: false, code: "remote_host_unknown" });
    expect(await runOp("remote_key", dirs, {})).toMatchObject({ ok: false, code: "ssh_missing", i18n: { key: "remote_ssh_missing" } });
  });

  it("refuses an invalid host with the field at fault, and keeps a renamed host's id and home", async () => {
    expect(await runOp("remote_host_save", dirs, { host: { name: "a", auth: "key", address: "me@", port: 22 } })).toMatchObject({
      ok: false,
      code: "remote_host_invalid",
      i18n: { key: "remote_address_invalid", args: { field: "address", address: "me@" } },
    });
    const id = await saveHost(dirs, { name: "a", auth: "key", address: "me@a", port: 22 });
    const file = path.join(dirs.base, "pipkin", "ssh", "hosts.json");
    const saved: unknown = JSON.parse(await readFile(file, "utf8"));
    const hosts = isObject(saved) && Array.isArray(saved.hosts) ? saved.hosts.filter(isObject) : [];
    await writeFile(file, JSON.stringify({ version: 1, hosts: hosts.map((host) => ({ ...host, home: "/home/me" })) }));
    expect(await runOp("remote_host_save", dirs, { host: { id, name: "b", auth: "key", address: "me@a", port: 22 } })).toMatchObject({
      data: { host: { id, name: "b", home: "/home/me" } },
    });
    // Another machine, maybe: its home is learned again.
    expect(await runOp("remote_host_save", dirs, { host: { id, name: "b", auth: "key", address: "me@a", port: 2222 } })).toMatchObject({
      data: { host: { id, port: 2222, home: null } },
    });
  });

  it("makes Pipkin's key once, private, and named after the device", async () => {
    await linkSshTools(dirs.prefix);
    const first = await runOp("remote_key", dirs, {});
    const line = stringAt(first, "data", "publicKey");
    expect(line).toMatch(new RegExp(`^ssh-ed25519 \\S+ pipkin@${os.hostname().replaceAll(".", "\\.")}$`));
    expect(await runOp("remote_key", dirs, {})).toEqual(first);
    expect((await stat(path.join(dirs.base, "pipkin", "ssh", "id_ed25519"))).mode & 0o777).toBe(0o600);
    expect(await runOp("remote_hosts", dirs, {})).toMatchObject({ data: { sshInstalled: true, publicKey: line } });
  });

  describe("remote_install", () => {
    it("installs OpenSSH with progress", async () => {
      await fakePkg(dirs, "Setting up openssh (10.2p1) ...", 0);
      const progress: string[] = [];
      expect(await runMode("remote_install", dirs, {}, {}, progress)).toMatchObject({ ok: true });
      expect(progress).toEqual(["pkg install -y openssh", "Setting up openssh (10.2p1) ..."]);
    });

    it.each([
      ["E: Could not get lock /data/data/com.termux/files/usr/var/lib/dpkg/lock-frontend. It is held by process 123 (apt)", "remote_install_busy"],
      ["E: Failed to fetch https://packages.termux.dev/apt/termux-main/pool/main/o/openssh/openssh_10.2p1_aarch64.deb  Temporary failure resolving 'packages.termux.dev'", "remote_install_offline"],
      ["E: Unable to locate package openssh", "install_failed"],
    ])("reports %j as %s", async (output, code) => {
      await fakePkg(dirs, output, 100);
      expect(await runMode("remote_install", dirs, {})).toMatchObject({ ok: false, code });
    });
  });

  describe("remote_connect's password prompts", () => {
    /**
     * Stands in for `ssh` signing in with a password: it asks its askpass once, and when that answer is wrong asks again
     * through keyboard-interactive, as OpenSSH does for a password host.
     */
    beforeEach(async () => {
      await symlink((await run("/bin/sh", ["-c", "command -v sh"])).stdout.trim(), path.join(dirs.prefix, "bin", "sh"));
      const ssh = path.join(dirs.prefix, "bin", "ssh");
      await writeFile(
        ssh,
        `#!/bin/sh
answer=$("$SSH_ASKPASS" "me@box's password: ") || exit 255
if [ "$answer" = right ]; then printf '%s\\n' /home/me /usr/bin/bash /usr/bin/git; exit 0; fi
"$SSH_ASKPASS" "(me@box) Password: " >/dev/null
echo "me@box: Permission denied (password,keyboard-interactive)." >&2
exit 255
`,
      );
      await chmod(ssh, 0o755);
    });

    it("signs in with the password the app gives and saves the home", async () => {
      const id = await saveHost(dirs, { name: "box", auth: "password", address: "me@box", port: 22 });
      const { prompts, result } = await connect(dirs, id, () => "right");
      expect(prompts).toEqual([{ type: "pipkin_remote_prompt", id: 1, kind: "password", text: "me@box's password: " }]);
      expect(result).toMatchObject({ ok: true, data: { home: "/home/me", tools: { bash: true, git: true, rg: false } } });
      expect(await runOp("remote_hosts", dirs, {})).toMatchObject({ data: { hosts: [{ id, home: "/home/me" }] } });
    });

    it("ends with a refused sign-in on a wrong password instead of asking again", async () => {
      const id = await saveHost(dirs, { name: "box", auth: "password", address: "me@box", port: 22 });
      const { prompts, result } = await connect(dirs, id, () => "wrong");
      expect(prompts).toHaveLength(1);
      expect(result).toMatchObject({ ok: false, code: "remote_auth_failed", i18n: { key: "remote_auth_failed", args: { host: "box" } } });
    });

    it("stops when the app cancels the prompt", async () => {
      const id = await saveHost(dirs, { name: "box", auth: "password", address: "me@box", port: 22 });
      expect((await connect(dirs, id, () => null)).result).toMatchObject({ ok: false, code: "remote_connect_cancelled" });
    });
  });
});

describe.skipIf(unavailable !== null)(`remote_connect against a throwaway sshd${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let dirs: Dirs;
  let sshd: TestSshd;
  let id: string;
  const knownHosts = (): Promise<string> => readFile(path.join(dirs.base, "pipkin", "ssh", "known_hosts"), "utf8").catch(() => "");

  beforeEach(async () => {
    dirs = await shortDirs();
    await linkSshTools(dirs.prefix);
    const key = stringAt(await runOp("remote_key", dirs, {}), "data", "publicKey");
    sshd = await startSshd(path.join(dirs.base, "sshd"), { authorizedKeys: key });
    id = await saveHost(dirs, { name: "box", auth: "key", address: `${user}@127.0.0.1`, port: sshd.port });
  });

  afterEach(async () => {
    await runOp("remote_disconnect", dirs, { id });
    await sshd.stop();
    await rm(dirs.base, { recursive: true, force: true });
  });

  it("asks about the host key with its fingerprint, connects, and leaves the connection up", async () => {
    const { prompts, result } = await connect(dirs, id, () => "yes");
    expect(prompts).toEqual([expect.objectContaining({ kind: "host_key", keyType: "ED25519", fingerprint: sshd.fingerprint })]);
    expect(result).toMatchObject({ ok: true, data: { home: os.userInfo().homedir, tools: { bash: true } } });
    expect(await knownHosts()).toContain(sshd.hostKey);
    expect(await runOp("remote_status", dirs, { ids: [id, "ffffffffffff"] })).toMatchObject({ data: { connected: { [id]: true, ffffffffffff: false } } });
    await runOp("remote_disconnect", dirs, { id });
    expect(await runOp("remote_status", dirs, { ids: [id] })).toMatchObject({ data: { connected: { [id]: false } } });

    // Known now, so the next connect asks nothing; forgetting the key makes it ask again.
    expect((await connect(dirs, id, () => "yes")).prompts).toEqual([]);
    await runOp("remote_disconnect", dirs, { id });
    expect(await runOp("remote_forget_host_key", dirs, { id })).toMatchObject({ ok: true });
    expect(await knownHosts()).not.toContain(sshd.hostKey);
    expect((await connect(dirs, id, () => "yes")).prompts).toHaveLength(1);
  });

  it("writes nothing to known_hosts when the host key is refused", async () => {
    expect((await connect(dirs, id, () => null)).result).toMatchObject({ ok: false, code: "remote_connect_cancelled" });
    expect(await knownHosts()).toBe("");
    expect(await runOp("remote_status", dirs, { ids: [id] })).toMatchObject({ data: { connected: { [id]: false } } });
  });

  it("replaces Pipkin's key, closing the old key's connection; the host refuses until it has the new line", async () => {
    expect((await connect(dirs, id, () => "yes")).result).toMatchObject({ ok: true });
    const old = stringAt(await runOp("remote_key", dirs, {}), "data", "publicKey");
    const line = stringAt(await runOp("remote_key_new", dirs, {}), "data", "publicKey");
    expect(line).toMatch(/^ssh-ed25519 \S+ pipkin@/);
    expect(line.split(" ")[1]).not.toBe(old.split(" ")[1]);
    expect((await stat(path.join(dirs.base, "pipkin", "ssh", "id_ed25519"))).mode & 0o777).toBe(0o600);
    expect(await runOp("remote_hosts", dirs, {})).toMatchObject({ data: { publicKey: line } });
    expect(await runOp("remote_status", dirs, { ids: [id] })).toMatchObject({ data: { connected: { [id]: false } } });

    expect((await connect(dirs, id, () => "yes")).result).toMatchObject({ ok: false, code: "remote_auth_failed" });
    await writeFile(path.join(dirs.base, "sshd", "authorized_keys"), `${line}\n`);
    expect((await connect(dirs, id, () => "yes")).result).toMatchObject({ ok: true });
  });

  it("asks for the key's passphrase, and refuses a wrong one without asking again", async () => {
    await run("ssh-keygen", ["-q", "-p", "-P", "", "-N", "secret", "-f", path.join(dirs.base, "pipkin", "ssh", "id_ed25519")]);
    const wrong = await connect(dirs, id, (prompt) => (prompt.kind === "host_key" ? "yes" : "wrong"));
    expect(wrong.prompts.map((prompt) => prompt.kind)).toEqual(["host_key", "passphrase"]);
    expect(wrong.result).toMatchObject({ ok: false, code: "remote_auth_failed" });
    const right = await connect(dirs, id, () => "secret");
    expect(right.prompts.map((prompt) => prompt.kind)).toEqual(["passphrase"]);
    expect(right.result).toMatchObject({ ok: true });
  });
});
