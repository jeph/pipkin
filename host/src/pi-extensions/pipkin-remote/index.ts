/**
 * Pipkin remote: in a remote workspace's chat, runs Pi's `read`, `write`, `edit` and `bash` tools, and the user's `!`
 * commands, on the remote folder over SSH. Pi itself stays on the phone; its working directory there is the workspace's
 * stand-in folder (`../../remote/stand-ins.ts`), and in any other folder the extension does nothing. `rpc` loads it
 * only for stand-ins, and it can't be turned off.
 *
 * Adapted from Pi 1.5.0's SSH example, `examples/extensions/ssh.ts` (https://github.com/earendil-works/pi),
 * Copyright (c) 2025 Mario Zechner, MIT License; `imageMimeType` follows Pi's `utils/mime.ts`. Pipkin's changes: the
 * host comes from the stand-in instead of a flag; every call goes through Pipkin's shared SSH connection
 * (`../../remote/ssh.ts`); contents travel on standard input; commands run under the remote watchdog, so Stop and
 * timeouts end them there; `~` means the remote home; the full output of a cut-off command, kept on the phone, stays
 * readable; `grep`, `find` and `ls` are off; and the app hears when the connection goes and comes back.
 */
import { dirname, isAbsolute, join } from "node:path";
import {
  type BashOperations,
  createBashToolDefinition,
  createEditToolDefinition,
  createReadToolDefinition,
  createWriteToolDefinition,
  type EditOperations,
  type ExtensionAPI,
  type ExtensionContext,
  type ExtensionFactory,
  getAgentDir,
  type ReadOperations,
  type WriteOperations,
} from "@earendil-works/pi-coding-agent";
import { HostError } from "../../env.js";
import { isObject } from "../../framing.js";
import { errorJson, notConnected } from "../../remote/errors.js";
import { accessRemote, mkdirRemote, readRemoteFile, RemoteFileError, remoteHome, writeRemoteFile } from "../../remote/fs.js";
import { checkConnection, isConnected, runWatched } from "../../remote/ssh.js";
import { pipkinChannel } from "../pipkin-channel.js";
import { expandRemoteHome, remoteWorkspace, type RemoteWorkspace, toRemotePath } from "./workspace.js";

/**
 * The channel topic. To the app: `{ connected, error? }` when a tool call finds the connection gone, or back, and after
 * every check; `{ reconnect: true }` when a call needs a password host that has no connection, asking the app to sign
 * in again with the saved password. From the app: `{ action: "check" }`, which it sends every few seconds while the chat is on
 * screen, and `{ action: "reconnected", connected }`, its answer to a reconnect.
 */
export const TOPIC = "remote";

/** Pi's tools that would search the phone's empty stand-in folder; a remote chat searches with `bash` instead. */
export const INACTIVE_TOOLS: readonly string[] = ["grep", "find", "ls"];

/** Failures that mean the host can't be reached or signed in to, which the app shows as a banner until they pass. */
const CONNECTION_CODES: Record<string, true> = {
  ssh_missing: true,
  remote_not_connected: true,
  remote_host_key_unknown: true,
  remote_host_key_changed: true,
  remote_auth_failed: true,
  remote_unreachable: true,
};

/**
 * What one Pi process knows across every load of the extension (Pi loads it again for each session and `/reload`):
 * whether the connection last failed, the phone's full-output files the model may read, the remote home, and the
 * workspace the latest load read, which the app's checks reach.
 */
interface State {
  /** The connection failure the app was last told about; undefined while the connection works. */
  down: HostError | undefined;
  /** Every `fullOutputPath` this Pi's `bash` returned: cut-off output, kept on the phone. */
  outputs: Set<string>;
  home: Promise<string> | undefined;
  workspace: RemoteWorkspace | undefined;
  /** Whether a check is running; one the app asks for meanwhile is dropped, as the running one answers it. */
  checking: boolean;
  /** The app's answer to the reconnect asked for last, while calls wait on it. */
  reconnect: Promise<boolean> | undefined;
  /** Settles [reconnect]; the app's `reconnected` message calls it. */
  reconnected: ((connected: boolean) => void) | undefined;
}

const SHARED = Symbol.for("pipkin.remote");

/** The process's [State], made on first use, which is when the app's checks start being answered. */
function sharedState(): State {
  const global = globalThis as { [SHARED]?: State };
  if (global[SHARED] !== undefined) return global[SHARED];
  const state: State = {
    down: undefined,
    outputs: new Set(),
    home: undefined,
    workspace: undefined,
    checking: false,
    reconnect: undefined,
    reconnected: undefined,
  };
  global[SHARED] = state;
  pipkinChannel().on(TOPIC, (data) => {
    if (!isObject(data)) return;
    if (data.action === "check") void check(state);
    if (data.action === "reconnected") state.reconnected?.(data.connected === true);
  });
  return state;
}

/**
 * Checks the connection for the app and tells it the outcome, every time: the app takes the banner down when the user
 * connects, and a host lost again just after must still bring it back. Without a connection, a host only the user can
 * sign in to isn't connected to again: a password host, or one whose sign-in already needed the user.
 */
async function check(state: State): Promise<void> {
  const workspace = state.workspace;
  if (workspace === undefined || state.checking) return;
  state.checking = true;
  try {
    const needsUser = workspace.host.auth === "password" || state.down?.code === "remote_not_connected";
    await checkConnection(workspace, !needsUser);
    state.down = undefined;
    pipkinChannel().send(TOPIC, { connected: true });
  } catch (error) {
    if (!(error instanceof HostError) || !CONNECTION_CODES[error.code]) return;
    state.down = error;
    pipkinChannel().send(TOPIC, { connected: false, error: errorJson(error) });
  } finally {
    state.checking = false;
  }
}

/** Tells the app the connection is gone, once per kind of failure. */
function markDown(state: State, error: HostError): void {
  if (state.down?.code === error.code) return;
  state.down = error;
  pipkinChannel().send(TOPIC, { connected: false, error: errorJson(error) });
}

/** Tells the app the connection is back, when it had been told it was gone. */
function markUp(state: State): void {
  if (state.down === undefined) return;
  state.down = undefined;
  pipkinChannel().send(TOPIC, { connected: true });
}

/**
 * How long a call to a password host with no connection waits for the app to sign in again before failing. Read at each call,
 * so tests can shorten it.
 */
function reconnectWaitMs(): number {
  return Number(process.env.PIPKIN_REMOTE_RECONNECT_WAIT_MS ?? 30_000);
}

/**
 * Asks the app to sign in to the host again, as it can with a password the user saved, and resolves to whether it
 * did; false when it couldn't, didn't answer within [reconnectWaitMs], or [signal] aborted first. One ask serves every
 * call that needs the host meanwhile.
 */
function askReconnect(state: State, signal: AbortSignal | undefined): Promise<boolean> {
  if (state.reconnect === undefined) {
    const { promise, resolve } = Promise.withResolvers<boolean>();
    const settle = (connected: boolean): void => {
      clearTimeout(timer);
      state.reconnect = undefined;
      state.reconnected = undefined;
      resolve(connected);
    };
    const timer = setTimeout(() => settle(false), reconnectWaitMs());
    state.reconnect = promise;
    state.reconnected = settle;
    pipkinChannel().send(TOPIC, { reconnect: true });
  }
  const answer = state.reconnect;
  if (signal === undefined) return answer;
  if (signal.aborted) return Promise.resolve(false);
  const { promise, resolve } = Promise.withResolvers<boolean>();
  const stop = (): void => resolve(false);
  signal.addEventListener("abort", stop, { once: true });
  void answer.then((connected) => {
    signal.removeEventListener("abort", stop);
    resolve(connected);
  });
  return promise;
}

/**
 * Runs remote [work], keeping the app's view of the connection current. A failure of `ssh` itself reaches the model
 * as `ssh`'s own message in a plain error, without the `code` Pi's tools would otherwise report instead of it.
 *
 * A password host signs in only with a password, which no call sends, so without a connection it first asks the app
 * to sign in again, as it can with a password the user saved. Asking before [work] rather than after its sign-in was
 * refused keeps `ssh`'s refusal out of a command's streamed output.
 */
async function remoteCall<T>(state: State, work: () => Promise<T>, signal?: AbortSignal): Promise<T> {
  try {
    const workspace = state.workspace;
    if (workspace?.host.auth === "password" && !(await isConnected(workspace)) && !(await askReconnect(state, signal))) {
      throw notConnected(workspace.host.name);
    }
    const result = await work();
    markUp(state);
    return result;
  } catch (error) {
    // A file that isn't there still came back over a working connection.
    if (error instanceof RemoteFileError) markUp(state);
    if (!(error instanceof HostError)) throw error;
    if (CONNECTION_CODES[error.code]) markDown(state, error);
    throw new Error(error.message, { cause: error });
  }
}

/** The remote home folder: the host's, saved at its first connect, or asked for once. */
function home(state: State, workspace: RemoteWorkspace): Promise<string> {
  if (workspace.host.home !== null) return Promise.resolve(workspace.host.home);
  state.home ??= remoteCall(state, () => remoteHome(workspace)).catch((error: unknown) => {
    state.home = undefined;
    throw error;
  });
  return state.home;
}

/** [path] with a leading `~` made the remote home, which Pi would otherwise take for the phone's. */
async function remotePathParam(state: State, workspace: RemoteWorkspace, path: string): Promise<string> {
  if (!/^@?~(\/|$)/.test(path)) return path;
  return expandRemoteHome(path, await home(state, workspace)) ?? path;
}

/** [ctx] with the remote folder as its `cwd`, which Pi's tools resolve relative paths against. */
function inRemoteFolder<T extends object>(ctx: T, cwd: string): T {
  return new Proxy(ctx, {
    get(target, key) {
      if (key === "cwd") return cwd;
      const value: unknown = Reflect.get(target, key, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function ascii(bytes: Buffer, offset: number, text: string): boolean {
  return bytes.length >= offset + text.length && bytes.toString("latin1", offset, offset + text.length) === text;
}

/** Whether a PNG is animated: an `acTL` chunk before the first `IDAT`. Pi's read tool doesn't take those. */
function animatedPng(bytes: Buffer): boolean {
  let offset = PNG.length;
  while (offset + 8 <= bytes.length) {
    if (ascii(bytes, offset + 4, "acTL")) return true;
    if (ascii(bytes, offset + 4, "IDAT")) return false;
    const next = offset + 12 + bytes.readUInt32BE(offset);
    if (next <= offset || next > bytes.length) return false;
    offset = next;
  }
  return false;
}

/** Whether [bytes] are a BMP Pi can show: a plausible header with one plane and a usual bit depth. */
function bmp(bytes: Buffer): boolean {
  if (bytes.length < 30 || !ascii(bytes, 0, "BM")) return false;
  const size = bytes.readUInt32LE(2);
  const pixels = bytes.readUInt32LE(10);
  const header = bytes.readUInt32LE(14);
  if ((size !== 0 && size < 26) || pixels < 14 + header || (size !== 0 && pixels >= size)) return false;
  const at = header === 12 ? 22 : header >= 40 && header <= 124 ? 26 : -1;
  return at > 0 && bytes.readUInt16LE(at) === 1 && [1, 4, 8, 16, 24, 32].includes(bytes.readUInt16LE(at + 2));
}

/** The image type Pi's read tool shows [bytes] as, or null for anything else, which it reads as text. */
export function imageMimeType(bytes: Buffer): string | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return bytes[3] === 0xf7 ? null : "image/jpeg";
  if (bytes.length >= 16 && PNG.every((byte, index) => bytes[index] === byte)) {
    return bytes.readUInt32BE(8) === 13 && ascii(bytes, 12, "IHDR") && !animatedPng(bytes) ? "image/png" : null;
  }
  if (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a")) return "image/gif";
  if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return "image/webp";
  return bmp(bytes) ? "image/bmp" : null;
}

/** The read tool's file operations for one call. The file comes over once and serves all three of them. */
function readOperations(state: State, workspace: RemoteWorkspace, signal: AbortSignal | undefined): ReadOperations {
  const files = new Map<string, Promise<Buffer>>();
  const load = (path: string): Promise<Buffer> => {
    const remotePath = toRemotePath(workspace, path);
    let file = files.get(remotePath);
    if (file === undefined) {
      file = remoteCall(state, () => readRemoteFile(workspace, remotePath, { signal }), signal);
      files.set(remotePath, file);
    }
    return file;
  };
  return {
    access: async (path) => {
      await load(path);
    },
    detectImageMimeType: async (path) => imageMimeType(await load(path)),
    readFile: load,
  };
}

/**
 * Writes contents through standard input. A write isn't aborted half way, which would leave the file cut short; Pi's
 * tools check for Stop between operations.
 */
function writeOperations(state: State, workspace: RemoteWorkspace): WriteOperations {
  return {
    writeFile: (path, content) => remoteCall(state, () => writeRemoteFile(workspace, toRemotePath(workspace, path), content)),
    mkdir: (dir) => remoteCall(state, () => mkdirRemote(workspace, toRemotePath(workspace, dir))),
  };
}

function editOperations(state: State, workspace: RemoteWorkspace, signal: AbortSignal | undefined): EditOperations {
  return {
    readFile: (path) => remoteCall(state, () => readRemoteFile(workspace, toRemotePath(workspace, path), { signal }), signal),
    access: (path) => remoteCall(state, () => accessRemote(workspace, toRemotePath(workspace, path), { signal }), signal),
    writeFile: writeOperations(state, workspace).writeFile,
  };
}

/**
 * Runs commands under the remote watchdog, streaming standard output and error as they arrive. Stop and a timeout
 * kill the local `ssh`, which ends the command's process group on the remote too.
 */
function remoteBashOperations(state: State, workspace: RemoteWorkspace): BashOperations {
  return {
    async exec(command, cwd, { onData, signal, timeout }) {
      if (timeout !== undefined && !(Number.isFinite(timeout) && timeout > 0)) {
        throw new Error("Invalid timeout: must be a finite number of seconds");
      }
      try {
        const result = await remoteCall(
          state,
          () =>
            runWatched(workspace, command, toRemotePath(workspace, cwd), {
              onStdout: onData,
              onStderr: onData,
              signal,
              ...(timeout === undefined ? {} : { timeoutMs: timeout * 1000 }),
            }),
          signal,
        );
        return { exitCode: result.code };
      } catch (error) {
        // Pi's bash tool and `!` commands recognise these two by their messages.
        if (signal?.aborted) throw new Error("aborted", { cause: error });
        if (error instanceof Error && error.name === "TimeoutError") throw new Error(`timeout:${timeout}`, { cause: error });
        throw error;
      }
    },
  };
}

/**
 * Whether [path] is a file the phone made for the model to read: the full output of a cut-off `bash` call or `!`
 * command, or the log of a `pipkin-background` task, which its results point the model at. Those are local; every
 * other path is the remote's.
 */
function isLocalOutput(state: State, path: string, ctx: ExtensionContext): boolean {
  const file = path.startsWith("@") ? path.slice(1) : path;
  if (!isAbsolute(file)) return false;
  if (state.outputs.has(file)) return true;
  if (dirname(file) === join(dirname(getAgentDir()), "background") && file.endsWith(".log")) return true;
  return ctx.sessionManager
    .getEntries()
    .some((entry) => entry.type === "message" && entry.message.role === "bashExecution" && entry.message.fullOutputPath === file);
}

/** Notes the phone's full-output file in a `bash` result or update, so `read` serves it from the phone. */
function rememberOutput(state: State, details: unknown): void {
  const path = typeof details === "object" && details !== null && "fullOutputPath" in details ? details.fullOutputPath : undefined;
  if (typeof path === "string") state.outputs.add(path);
}

function registerTools(pi: ExtensionAPI, state: State, workspace: RemoteWorkspace): void {
  const remoteCwd = workspace.path;
  const localRead = createReadToolDefinition(workspace.standIn);
  pi.registerTool({
    ...createReadToolDefinition(remoteCwd),
    async execute(toolCallId, params, signal, onUpdate, ctx) {
      if (isLocalOutput(state, params.path, ctx)) return localRead.execute(toolCallId, params, signal, onUpdate, ctx);
      const read = createReadToolDefinition(remoteCwd, { operations: readOperations(state, workspace, signal) });
      const path = await remotePathParam(state, workspace, params.path);
      return read.execute(toolCallId, { ...params, path }, signal, onUpdate, inRemoteFolder(ctx, remoteCwd));
    },
  });

  const write = createWriteToolDefinition(remoteCwd, { operations: writeOperations(state, workspace) });
  pi.registerTool({
    ...write,
    async execute(toolCallId, params, signal, onUpdate, ctx) {
      const path = await remotePathParam(state, workspace, params.path);
      return write.execute(toolCallId, { ...params, path }, signal, onUpdate, inRemoteFolder(ctx, remoteCwd));
    },
  });

  pi.registerTool({
    ...createEditToolDefinition(remoteCwd),
    async execute(toolCallId, params, signal, onUpdate, ctx) {
      const edit = createEditToolDefinition(remoteCwd, { operations: editOperations(state, workspace, signal) });
      const path = await remotePathParam(state, workspace, params.path);
      return edit.execute(toolCallId, { ...params, path }, signal, onUpdate, inRemoteFolder(ctx, remoteCwd));
    },
  });

  // The PI_* variables Pi sets for a local command don't reach the remote, so its tool doesn't offer them.
  const bash = createBashToolDefinition(remoteCwd, { operations: remoteBashOperations(state, workspace), exposeSessionEnvironment: false });
  pi.registerTool({
    ...bash,
    async execute(toolCallId, params, signal, onUpdate, ctx) {
      // Updates carry the output file as soon as the output is cut, which a Stop's error result no longer does.
      const result = await bash.execute(
        toolCallId,
        params,
        signal,
        (update) => {
          rememberOutput(state, update.details);
          onUpdate?.(update);
        },
        inRemoteFolder(ctx, remoteCwd),
      );
      rememberOutput(state, result.details);
      return result;
    },
  });
}

/** A remote chat's tools once its host is gone: each says so, rather than working on the empty stand-in. */
function registerRefusals(pi: ExtensionAPI, cwd: string, error: HostError): void {
  const refuse = (): never => {
    throw new Error(error.message);
  };
  pi.registerTool({ ...createReadToolDefinition(cwd), execute: refuse });
  pi.registerTool({ ...createWriteToolDefinition(cwd), execute: refuse });
  pi.registerTool({ ...createEditToolDefinition(cwd), execute: refuse });
  pi.registerTool({ ...createBashToolDefinition(cwd), execute: refuse });
  pi.on("user_bash", () => ({ result: { output: error.message, exitCode: 1, cancelled: false, truncated: false } }));
}

/** Turns `grep`, `find` and `ls` off, keeping every other tool as it is, the tools of other extensions included. */
function deactivateLocalSearch(pi: ExtensionAPI): void {
  const active = pi.getActiveTools();
  if (active.some((name) => INACTIVE_TOOLS.includes(name))) pi.setActiveTools(active.filter((name) => !INACTIVE_TOOLS.includes(name)));
}

/** The extension for a Pi working in [cwd], which does nothing unless [cwd] is a stand-in. */
export function createPipkinRemote(cwd: string): ExtensionFactory {
  return async (pi) => {
    let workspace: RemoteWorkspace | null;
    try {
      workspace = await remoteWorkspace(cwd);
    } catch (error) {
      if (!(error instanceof HostError)) throw error;
      registerRefusals(pi, cwd, error);
      pi.on("session_start", () => deactivateLocalSearch(pi));
      return;
    }
    if (workspace === null) return;
    const remote = workspace;
    const state = sharedState();
    state.workspace = remote;
    registerTools(pi, state, remote);
    pi.on("user_bash", () => ({ operations: remoteBashOperations(state, remote) }));
    pi.on("session_start", () => deactivateLocalSearch(pi));
    // The model works in the remote folder, not in the stand-in Pi runs in.
    pi.on("before_agent_start", (event) => {
      event.systemPromptOptions.cwd = remote.path;
    });
  };
}

export default createPipkinRemote(process.cwd());
