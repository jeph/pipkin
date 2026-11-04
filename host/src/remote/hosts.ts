import { execFile } from "node:child_process";
import { randomBytes } from "node:crypto";
import { chmod, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { HostError } from "../env.js";
import { isObject } from "../framing.js";
import { errorCode, hostUnknown } from "./errors.js";
import { hostsFile, keyFile, knownHostsFile, type RemotePaths, socketsDir, sshConfigFile } from "./paths.js";

export type HostAuth = "key" | "password" | "config";

/** A host the user added, as `hosts.json` and the app hold it. */
export interface Host {
  /** 12 random hex digits, fixed for the host's life: names its control socket and its stand-ins. */
  id: string;
  name: string;
  auth: HostAuth;
  /** `user@host`, `host` or `user@[v6]`; for `config` hosts, a `Host` alias from `~/.ssh/config`. */
  address: string;
  /** Null for `config` hosts, whose port comes from `~/.ssh/config`. */
  port: number | null;
  /** The remote home folder, learned at the first connect. */
  home: string | null;
}

const AUTHS: Record<string, HostAuth> = { key: "key", password: "password", config: "config" };
const ID = /^[0-9a-f]{12}$/;
const CONTROL = /\p{Cc}/u;
/** A host name or IPv4 address: no `:`, so `host:22` is refused, and no leading `-`, which `ssh` takes for an option. */
const HOST_NAME = /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/;
/** An IPv6 address inside brackets, with an optional zone. */
const IPV6 = /^\[([0-9A-Fa-f.]*:[0-9A-Fa-f:.]*(?:%[A-Za-z0-9_.-]+)?)\]$/;
/** A user name: anything a config line keeps as one word, so no space, quote or `#`, and no leading `-`. */
const USER = /^[^\s"'#\\@-][^\s"'#]*$/u;
/** A `Host` alias: one plain word, never a pattern or an option. */
const ALIAS = /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/;

/** The parts of a `key` or `password` host's address; null when it isn't valid. */
export function parseAddress(address: string): { user: string | null; host: string } | null {
  const at = address.lastIndexOf("@");
  const user = at < 0 ? null : address.slice(0, at);
  const host = address.slice(at + 1);
  if (user !== null && !USER.test(user)) return null;
  const v6 = IPV6.exec(host);
  if (v6?.[1] !== undefined) return { user, host: v6[1] };
  return HOST_NAME.test(host) ? { user, host } : null;
}

function invalid(field: "name" | "address" | "port", key: string, message: string, args: Record<string, string> = {}): HostError {
  return new HostError("remote_host_invalid", message, { key, args: { field, ...args } });
}

/** A host the app asks to save: without `id` for a new host. */
export interface HostDraft {
  id: string | null;
  name: string;
  auth: HostAuth;
  address: string;
  port: number | null;
}

/**
 * Checks the host the app sent against the saved [hosts], throwing `remote_host_invalid` with the field at fault in
 * `i18n.args.field`. Names are compared ignoring case, since the sidebar tells hosts apart by name.
 */
export function validateHost(value: unknown, hosts: readonly Host[]): HostDraft {
  if (!isObject(value)) throw new HostError("bad_params", "Missing host");
  const id = value.id === undefined || value.id === null ? null : value.id;
  if (id !== null && (typeof id !== "string" || !ID.test(id))) throw new HostError("bad_params", "Bad host id");
  const auth = typeof value.auth === "string" ? AUTHS[value.auth] : undefined;
  if (auth === undefined) throw new HostError("bad_params", "Bad host sign-in method");

  const name = typeof value.name === "string" ? value.name.trim() : "";
  if (name === "") throw invalid("name", "remote_name_empty", "Enter a name.");
  if (CONTROL.test(name)) throw invalid("name", "remote_name_invalid", "The name can't contain control characters.");
  const folded = name.toLocaleLowerCase("en");
  if (hosts.some((host) => host.id !== id && host.name.toLocaleLowerCase("en") === folded)) {
    throw invalid("name", "remote_name_taken", `Another host is named ${name}.`, { name });
  }

  const address = typeof value.address === "string" ? value.address.trim() : "";
  if (address === "") throw invalid("address", "remote_address_empty", "Enter an address.");
  if (auth === "config") {
    if (!ALIAS.test(address)) throw invalid("address", "remote_alias_invalid", `${address} isn't a Host alias.`, { alias: address });
    return { id, name, auth, address, port: null };
  }
  if (parseAddress(address) === null) {
    throw invalid("address", "remote_address_invalid", `${address} isn't an address like user@host.`, { address });
  }
  const port = value.port;
  if (typeof port !== "number" || !Number.isInteger(port) || port < 1 || port > 65_535) {
    throw invalid("port", "remote_port_invalid", "The port must be a number from 1 to 65535.", { port: String(port) });
  }
  return { id, name, auth, address, port };
}

/** The saved host, or null when [value] isn't one: a hand-edited `hosts.json` drops bad entries, not every host. */
function parseHost(value: unknown): Host | null {
  if (!isObject(value)) return null;
  const { id, name, auth, address, port, home } = value;
  if (typeof id !== "string" || !ID.test(id) || typeof name !== "string" || typeof address !== "string") return null;
  const method = typeof auth === "string" ? AUTHS[auth] : undefined;
  if (method === undefined) return null;
  if (method === "config") return ALIAS.test(address) ? { id, name, auth: method, address, port: null, home: typeof home === "string" ? home : null } : null;
  if (parseAddress(address) === null) return null;
  if (typeof port !== "number" || !Number.isInteger(port) || port < 1 || port > 65_535) return null;
  return { id, name, auth: method, address, port, home: typeof home === "string" ? home : null };
}

/** The saved hosts; none before the first is added. */
export async function loadHosts(paths: RemotePaths): Promise<Host[]> {
  let text: string;
  try {
    text = await readFile(hostsFile(paths), "utf8");
  } catch (error) {
    if (errorCode(error) === "ENOENT") return [];
    throw error;
  }
  const parsed: unknown = JSON.parse(text);
  if (!isObject(parsed) || !Array.isArray(parsed.hosts)) return [];
  return parsed.hosts.map(parseHost).filter((host) => host !== null);
}

/** Saved host [id]; `remote_host_unknown` when it was removed. */
export async function findHost(paths: RemotePaths, id: string): Promise<Host> {
  const host = (await loadHosts(paths)).find((candidate) => candidate.id === id);
  if (host === undefined) throw hostUnknown();
  return host;
}

export function newHostId(): string {
  return randomBytes(6).toString("hex");
}

/** The name `ssh` connects to: Pipkin's own `Host` block, or the user's alias. */
export function destination(host: Host): string {
  return host.auth === "config" ? host.address : `pipkin-${host.id}`;
}

/** [value] as one word of an OpenSSH config line or `-o` option. */
export function configWord(value: string): string {
  return /\s/.test(value) ? `"${value}"` : value;
}

/**
 * The OpenSSH config generated from [hosts]; `config` hosts need no block, as `~/.ssh/config` has theirs. With
 * [quietWeakCrypto], for a client that knows `WarnWeakCrypto` (10.1+), it keeps the post-quantum warning off stderr,
 * where it would land in every tool output.
 */
export function sshConfig(paths: RemotePaths, hosts: readonly Host[], quietWeakCrypto: boolean): string {
  const lines = [
    "# Written by Pipkin from hosts.json; changes here are overwritten.",
    "Host *",
    // A silent server ends the connection after (count + 1) unanswered intervals: 45 s.
    "  ServerAliveInterval 15",
    "  ServerAliveCountMax 2",
    "  ConnectTimeout 15",
    `  UserKnownHostsFile ${configWord(knownHostsFile(paths))} ${configWord(join(paths.home, ".ssh", "known_hosts"))}`,
    "  HashKnownHosts no",
    ...(quietWeakCrypto ? ["  WarnWeakCrypto no"] : []),
  ];
  for (const host of hosts) {
    const address = host.auth === "config" ? null : parseAddress(host.address);
    if (address === null) continue;
    lines.push("", `Host ${destination(host)}`, `  HostName ${address.host}`);
    if (address.user !== null) lines.push(`  User ${address.user}`);
    lines.push(
      `  Port ${String(host.port)}`,
      `  IdentityFile ${configWord(keyFile(paths))}`,
      "  IdentitiesOnly yes",
      `  PreferredAuthentications ${host.auth === "password" ? "password,keyboard-interactive" : "publickey"}`,
    );
  }
  lines.push("", "Match all", `  Include ${configWord(join(paths.home, ".ssh", "config"))}`, "");
  return lines.join("\n");
}

/** Makes the SSH folder and its sockets folder, private to Termux's user as OpenSSH wants. */
export async function ensureSshDir(paths: RemotePaths): Promise<void> {
  await mkdir(socketsDir(paths), { recursive: true, mode: 0o700 });
  await chmod(paths.ssh, 0o700);
}

/** Replaces [path] at once, so a reader never sees half a file. */
export async function writeAtomically(path: string, text: string, mode = 0o600): Promise<void> {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, text, { mode });
  await rename(temporary, path);
}

let knowsWarnWeakCrypto = false;

/**
 * Whether Termux's `ssh` knows `WarnWeakCrypto`. An older one refuses a config naming it, and `IgnoreUnknown` can't
 * help: its first value wins, so ours would cancel the one in the user's own `~/.ssh/config`. Only a yes is kept, as
 * OpenSSH may be installed or updated while the process runs.
 */
async function supportsWarnWeakCrypto(paths: RemotePaths): Promise<boolean> {
  if (knowsWarnWeakCrypto) return true;
  const { promise, resolve } = Promise.withResolvers<boolean>();
  execFile(join(paths.bin, "ssh"), ["-G", "-F", "/dev/null", "-o", "WarnWeakCrypto=no", "pipkin-probe"], { timeout: 10_000 }, (error) => {
    resolve(error === null);
  });
  knowsWarnWeakCrypto = await promise;
  return knowsWarnWeakCrypto;
}

/** Writes `ssh/config` for the saved hosts, as every `ssh` call reads it. */
export async function writeSshConfig(paths: RemotePaths, hosts?: readonly Host[]): Promise<void> {
  await ensureSshDir(paths);
  const config = sshConfig(paths, hosts ?? (await loadHosts(paths)), await supportsWarnWeakCrypto(paths));
  await writeAtomically(sshConfigFile(paths), config);
}

/**
 * Writes `ssh/config` again from the saved hosts, so a change in what this Pipkin writes there reaches hosts saved by
 * an older one, which would otherwise keep its settings until they were edited. Nothing before the first host.
 */
export async function refreshSshConfig(paths: RemotePaths): Promise<void> {
  const hosts = await loadHosts(paths);
  if (hosts.length > 0) await writeSshConfig(paths, hosts);
}

let updates: Promise<unknown> = Promise.resolve();

/**
 * Applies [change] to the saved hosts and writes `hosts.json` and `ssh/config`. Updates in one process run one at a
 * time, so two saves at once can't drop each other's host.
 */
export function updateHosts<T>(paths: RemotePaths, change: (hosts: Host[]) => Promise<{ hosts: Host[]; result: T }> | { hosts: Host[]; result: T }): Promise<T> {
  const update = updates.then(async () => {
    const { hosts, result } = await change(await loadHosts(paths));
    await ensureSshDir(paths);
    await writeAtomically(hostsFile(paths), `${JSON.stringify({ version: 1, hosts }, null, 2)}\n`);
    await writeAtomically(sshConfigFile(paths), sshConfig(paths, hosts, await supportsWarnWeakCrypto(paths)));
    return result;
  });
  updates = update.catch(() => undefined);
  return update;
}

/** Saves [home] as host [id]'s home folder, which labels its folders `~/…`; nothing when it's already saved. */
export async function recordHome(paths: RemotePaths, id: string, home: string): Promise<void> {
  const saved = (await loadHosts(paths)).find((host) => host.id === id);
  if (saved === undefined || saved.home === home) return;
  await updateHosts(paths, (hosts) => ({
    hosts: hosts.map((host) => (host.id === id ? { ...host, home } : host)),
    result: undefined,
  }));
}
