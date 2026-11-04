/** The utility operations that manage remote hosts: the list, saving and removing hosts, the key, connections. */
import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringArrayParam, stringParam } from "../params.js";
import { hostUnknown } from "../remote/errors.js";
import { findHost, type Host, loadHosts, newHostId, updateHosts, validateHost } from "../remote/hosts.js";
import { ensureKey, publicKey } from "../remote/key.js";
import { remotePaths, type RemotePaths } from "../remote/paths.js";
import { createStandIn, listStandIns } from "../remote/stand-ins.js";
import { disconnect, forgetHostKey, isConnected, sshInstalled } from "../remote/ssh.js";

function pathsOf(ctx: ModeContext): RemotePaths {
  return remotePaths(ctx.env.configDir, ctx.env.prefix, ctx.env.home);
}

/**
 * `{ sshInstalled, publicKey, hosts, workspaces }`: whether Termux has OpenSSH, Pipkin's public key line (null until
 * made), the saved hosts, and every stand-in, those of removed hosts included so their chats keep a label.
 */
export async function remoteHosts(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  const [installed, key, hosts, workspaces] = await Promise.all([
    sshInstalled(paths),
    publicKey(paths),
    loadHosts(paths),
    listStandIns(paths.remotes),
  ]);
  return { sshInstalled: installed, publicKey: key, hosts, workspaces };
}

/**
 * Saves `{ host }`, new without an `id`, and returns it as saved. A host whose address, port or sign-in changed is
 * disconnected, and its home is learned again at the next connect, as it may be another machine.
 */
export async function remoteHostSave(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  const { host, previous } = await updateHosts(paths, (hosts) => {
    const draft = validateHost(ctx.params.host, hosts);
    const existing = draft.id === null ? undefined : hosts.find((saved) => saved.id === draft.id);
    if (draft.id !== null && existing === undefined) throw hostUnknown();
    const moved = existing !== undefined && (existing.address !== draft.address || existing.port !== draft.port || existing.auth !== draft.auth);
    const saved: Host = {
      id: existing?.id ?? newHostId(),
      name: draft.name,
      auth: draft.auth,
      address: draft.address,
      port: draft.port,
      home: moved ? null : (existing?.home ?? null),
    };
    const next = existing === undefined ? [...hosts, saved] : hosts.map((other) => (other.id === saved.id ? saved : other));
    return { hosts: next, result: { host: saved, previous: moved ? existing : undefined } };
  });
  if (previous !== undefined) await disconnect({ paths, host: previous });
  return { host };
}

/** Disconnects and removes host `id`; its stand-ins stay, so archived chats keep their labels. */
export async function remoteHostRemove(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  const id = stringParam(ctx.params, "id");
  const removed = await updateHosts(paths, (hosts) => ({
    hosts: hosts.filter((host) => host.id !== id),
    result: hosts.find((host) => host.id === id),
  }));
  if (removed !== undefined) await disconnect({ paths, host: removed });
  return {};
}

/** `{ publicKey }`, making Pipkin's key first if needed. */
export async function remoteKey(ctx: ModeContext): Promise<JsonObject> {
  return { publicKey: await ensureKey(pathsOf(ctx)) };
}

/** `{ connected: { <id>: boolean } }` for each of `ids`, from `ssh -O check`; false for a host that's gone. */
export async function remoteStatus(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  const ids = stringArrayParam(ctx.params, "ids");
  const hosts = await loadHosts(paths);
  const states = await Promise.all(
    ids.map(async (id) => {
      const host = hosts.find((saved) => saved.id === id);
      return [id, host !== undefined && (await isConnected({ paths, host }))] as const;
    }),
  );
  return { connected: Object.fromEntries(states) };
}

/** Ends host `id`'s master connection. */
export async function remoteDisconnect(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  const id = stringParam(ctx.params, "id");
  const host = (await loadHosts(paths)).find((saved) => saved.id === id);
  if (host !== undefined) await disconnect({ paths, host });
  return {};
}

/** Forgets host `id`'s key in Pipkin's `known_hosts`, after the app warned that it changed. */
export async function remoteForgetHostKey(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  await forgetHostKey({ paths, host: await findHost(paths, stringParam(ctx.params, "id")) });
  return {};
}

/** `{ cwd }`: the stand-in for host `hostId`'s folder `path`, made if needed. */
export async function remoteWorkspace(ctx: ModeContext): Promise<JsonObject> {
  const paths = pathsOf(ctx);
  const host = await findHost(paths, stringParam(ctx.params, "hostId"));
  return { cwd: await createStandIn(paths.remotes, host.id, stringParam(ctx.params, "path")) };
}
