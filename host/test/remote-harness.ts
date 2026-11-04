/**
 * A remote workspace on a throwaway sshd, for the tests of what runs there from a chat: Pi's tools, background tasks,
 * subagents and attachments. The remote is this machine, reached over SSH as the user running the tests.
 */
import { execFile } from "node:child_process";
import { mkdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { promisify } from "node:util";
import { type Host, newHostId, updateHosts } from "../src/remote/hosts.js";
import { ensureKey } from "../src/remote/key.js";
import { knownHostsFile, remotePaths, type RemotePaths } from "../src/remote/paths.js";
import { createStandIn } from "../src/remote/stand-ins.js";
import { disconnect } from "../src/remote/ssh.js";
import type { Dirs } from "./harness.js";
import { linkSshTools, shortTempDir, startSshd, type TestSshd } from "./sshd.js";

export interface RemoteFixture {
  /** The folders a host started for this fixture runs with, short enough for its control sockets. */
  dirs: Dirs;
  paths: RemotePaths;
  sshd: TestSshd;
  host: Host;
  /** The remote folder, with every character a path can make awkward. */
  remoteDir: string;
  /** Its stand-in, the chat's `cwd`. */
  standIn: string;
  /** What `${XDG_CACHE_HOME}` is on the remote, so nothing lands in the real `~/.cache`. */
  remoteCache: string;
  stop(): Promise<void>;
}

/**
 * Starts sshd with Pipkin's key authorized and the host saved, as `remote_connect` leaves them: the host key known and
 * the remote home learned, here a folder of the fixture's own.
 */
export async function startRemoteFixture(): Promise<RemoteFixture> {
  const base = await shortTempDir();
  const dirs: Dirs = {
    base,
    root: path.join(base, "root"),
    configDir: path.join(base, "config"),
    home: path.join(base, "home"),
    prefix: path.join(base, "prefix"),
    shared: path.join(base, "shared"),
  };
  await Promise.all([dirs.root, dirs.configDir, dirs.home, dirs.shared].map((dir) => mkdir(dir, { recursive: true })));
  const remoteCache = path.join(base, "remote-cache");
  const remoteDir = path.join(base, `remote dir it's "$x" \`y\` * ü`);
  const remoteHome = path.join(base, "remote-home");
  await mkdir(remoteDir, { recursive: true });
  await mkdir(remoteHome, { recursive: true });
  const paths = remotePaths(dirs.configDir, dirs.prefix, dirs.home);
  await linkSshTools(dirs.prefix);
  const sshd = await startSshd(path.join(base, "sshd"), {
    authorizedKeys: await ensureKey(paths),
    forceCommand: `exec env XDG_CACHE_HOME=${remoteCache} /bin/sh -c "$SSH_ORIGINAL_COMMAND"`,
  });
  const host: Host = { id: newHostId(), name: "testbox", auth: "key", address: `${os.userInfo().username}@127.0.0.1`, port: sshd.port, home: remoteHome };
  await updateHosts(paths, (saved) => ({ hosts: [...saved, host], result: undefined }));
  await writeFile(knownHostsFile(paths), `[127.0.0.1]:${sshd.port} ${sshd.hostKey}\n`);
  const standIn = await createStandIn(paths.remotes, host.id, remoteDir);
  return {
    dirs,
    paths,
    sshd,
    host,
    remoteDir,
    standIn,
    remoteCache,
    async stop() {
      await disconnect({ paths, host });
      await sshd.stop();
      await rm(base, { recursive: true, force: true });
    },
  };
}

export function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/** [pid] and every process below it, from `ps`: what `docker pause` would freeze of a server. */
export async function processTree(pid: number): Promise<number[]> {
  const { stdout } = await promisify(execFile)("ps", ["-A", "-o", "pid=,ppid="]);
  const parents = stdout.trim().split("\n").map((line) => line.trim().split(/\s+/).map(Number));
  const tree = [pid];
  for (let index = 0; index < tree.length; index++) {
    for (const [child, parent] of parents) if (parent === tree[index] && child !== undefined) tree.push(child);
  }
  return tree;
}

/**
 * Sends [signal] to each of [pids] that still exists, and returns those. A process `ps` listed can end before the
 * signal reaches it, as a server's short-lived children do; any other failure throws.
 */
export function signalEach(pids: readonly number[], signal: NodeJS.Signals): number[] {
  return pids.filter((pid) => {
    try {
      process.kill(pid, signal);
      return true;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ESRCH") return false;
      throw error;
    }
  });
}

/**
 * Resolves once [check] holds; fails after [ms]. It polls, as what it waits for happens in processes sshd started,
 * which send no event a test could await.
 */
export async function eventually(check: () => Promise<boolean> | boolean, ms = 5000): Promise<void> {
  const deadline = Date.now() + ms;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error("Timed out waiting");
    await delay(50);
  }
}
