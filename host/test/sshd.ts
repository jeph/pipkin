import { execFile, spawn } from "node:child_process";
import { access, chmod, mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import type { Dirs } from "./harness.js";

const run = promisify(execFile);
const SSHD = "/usr/sbin/sshd";

/** A throwaway OpenSSH server on 127.0.0.1, logging in as the user running the tests. */
export interface TestSshd {
  port: number;
  /** The listening sshd's process; each connection's sshd runs below it. */
  pid: number;
  /** The server's public host key line, `ssh-ed25519 AAAA…`. */
  hostKey: string;
  /** The host key's `SHA256:` fingerprint. */
  fingerprint: string;
  /** What sshd logged so far. */
  log(): string;
  stop(): Promise<void>;
}

export interface SshdOptions {
  /** `authorized_keys` content. */
  authorizedKeys: string;
  maxSessions?: number;
  /** Replaces every command, as `ForceCommand`; the requested one is in `$SSH_ORIGINAL_COMMAND`. */
  forceCommand?: string;
  /** The server's `KexAlgorithms`, such as one without post-quantum exchange. */
  kexAlgorithms?: string;
}

/** A port nothing listens on, for now. */
export async function freePort(): Promise<number> {
  const server = createServer();
  const { promise, resolve } = Promise.withResolvers<void>();
  server.listen(0, "127.0.0.1", resolve);
  await promise;
  const address = server.address();
  const closed = Promise.withResolvers<void>();
  server.close(() => closed.resolve());
  await closed.promise;
  if (address === null || typeof address === "string") throw new Error("No port");
  return address.port;
}

/** A short folder under `/tmp`: socket paths inside it must fit the 104 bytes macOS allows. */
export async function shortTempDir(): Promise<string> {
  return realpath(await mkdtemp("/tmp/pk-"));
}

/**
 * Test folders as `makeDirs` gives them, under a short `/tmp` folder whose paths leave room for the control sockets
 * on macOS. Pipkin's SSH state is then `<base>/pipkin/ssh`.
 */
export async function shortDirs(): Promise<Dirs> {
  const base = await shortTempDir();
  const dirs = {
    base,
    root: path.join(base, "root"),
    configDir: path.join(base, "pipkin", "pi"),
    home: path.join(base, "home"),
    prefix: path.join(base, "prefix"),
    shared: path.join(base, "shared"),
  };
  await Promise.all([dirs.root, dirs.configDir, dirs.home, path.join(dirs.prefix, "bin"), dirs.shared].map((dir) => mkdir(dir, { recursive: true })));
  return dirs;
}

/** Links Termux's tools the remote code runs (`ssh`, `ssh-keygen`, `sh`) into [prefix]`/bin`. */
export async function linkSshTools(prefix: string): Promise<void> {
  const bin = path.join(prefix, "bin");
  await mkdir(bin, { recursive: true });
  for (const tool of ["ssh", "ssh-keygen", "sh"]) {
    const { stdout } = await run("/bin/sh", ["-c", `command -v ${tool}`]);
    await symlink(stdout.trim(), path.join(bin, tool));
  }
}

/** Starts sshd in [dir] with a new ed25519 host key, `UsePAM no` and `StrictModes no`, as root isn't available. */
export async function startSshd(dir: string, options: SshdOptions): Promise<TestSshd> {
  await mkdir(dir, { recursive: true });
  const hostKeyFile = path.join(dir, "host_key");
  await run("ssh-keygen", ["-q", "-t", "ed25519", "-N", "", "-f", hostKeyFile]);
  const authorizedKeys = path.join(dir, "authorized_keys");
  await writeFile(authorizedKeys, options.authorizedKeys);
  await chmod(authorizedKeys, 0o600);
  const port = await freePort();
  const config = [
    `Port ${port}`,
    "ListenAddress 127.0.0.1",
    `HostKey ${hostKeyFile}`,
    `AuthorizedKeysFile ${authorizedKeys}`,
    "UsePAM no",
    "StrictModes no",
    "PidFile none",
    "PasswordAuthentication no",
    "KbdInteractiveAuthentication no",
    `MaxSessions ${options.maxSessions ?? 10}`,
    ...(options.forceCommand === undefined ? [] : [`ForceCommand ${options.forceCommand}`]),
    ...(options.kexAlgorithms === undefined ? [] : [`KexAlgorithms ${options.kexAlgorithms}`]),
  ];
  const configFile = path.join(dir, "sshd_config");
  await writeFile(configFile, `${config.join("\n")}\n`);
  const child = spawn(SSHD, ["-f", configFile, "-D", "-e"], { stdio: ["ignore", "ignore", "pipe"] });
  let log = "";
  const listening = Promise.withResolvers<void>();
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk: string) => {
    log += chunk;
    if (log.includes("Server listening")) listening.resolve();
  });
  child.on("exit", () => listening.reject(new Error(`sshd exited: ${log}`)));
  const timer = setTimeout(() => listening.reject(new Error(`sshd didn't start: ${log}`)), 10_000);
  await listening.promise.finally(() => clearTimeout(timer));
  const hostKey = (await readFile(`${hostKeyFile}.pub`, "utf8")).trim().split(" ").slice(0, 2).join(" ");
  const { stdout } = await run("ssh-keygen", ["-l", "-E", "sha256", "-f", `${hostKeyFile}.pub`]);
  const fingerprint = stdout.split(" ")[1] ?? "";
  return {
    port,
    pid: child.pid ?? 0,
    hostKey,
    fingerprint,
    log: () => log,
    async stop() {
      if (child.exitCode !== null || child.signalCode !== null) return;
      const exited = Promise.withResolvers<void>();
      child.once("exit", () => exited.resolve());
      child.kill();
      await exited.promise;
    },
  };
}

/**
 * Why sshd tests can't run here, or null when they can: they need `/usr/sbin/sshd` and an sshd that lets this user in
 * without root. Under CI, which must run them (GitHub's Ubuntu runners have openssh-server), it throws instead.
 */
export async function sshdUnavailable(): Promise<string | null> {
  try {
    await access(SSHD);
  } catch {
    if (process.env.CI) throw new Error(`${SSHD} isn't installed`);
    return `${SSHD} isn't installed`;
  }
  const dir = await mkdtemp(path.join(os.tmpdir(), "pk-probe-"));
  const key = path.join(dir, "id");
  let sshd: TestSshd | undefined;
  try {
    await run("ssh-keygen", ["-q", "-t", "ed25519", "-N", "", "-f", key]);
    sshd = await startSshd(path.join(dir, "sshd"), { authorizedKeys: await readFile(`${key}.pub`, "utf8") });
    const options = ["-F", "/dev/null", "-i", key, "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no"];
    await run("ssh", [...options, "-o", `UserKnownHostsFile=${path.join(dir, "known_hosts")}`, "-p", String(sshd.port), "127.0.0.1", "true"]);
    return null;
  } catch (error) {
    const reason = `sshd can't log this user in without root: ${error instanceof Error ? error.message : String(error)}`;
    // CI must run these tests: a runner that can't is a failure to fix there, not a skip nobody sees.
    if (process.env.CI) throw new Error(reason, { cause: error });
    return reason;
  } finally {
    await sshd?.stop();
    await rm(dir, { recursive: true, force: true });
  }
}
