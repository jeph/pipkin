import { join, posix } from "node:path";
import { ASKPASS_SOCKET_VARIABLE, describePrompt, type RemotePrompt, startAskpassBridge } from "./askpass.js";
import { authFailed, bashMissing, classifySshFailure, connectCancelled, lastLine, remoteFailed } from "./errors.js";
import { recordHome, writeAtomically, writeSshConfig } from "./hosts.js";
import { quote } from "./script.js";
import { acquireSession, type Remote, type RunResult, spawnSsh, sshArgs, sshEnvironment } from "./ssh.js";

/**
 * The command a connect runs, in the login shell itself: POSIX, so it works without `bash` and reports its absence.
 * Prints the home folder, then the paths of the tools that are there.
 */
export const PROBE = `printf '%s\\n' "$HOME"; command -v bash; command -v git; command -v rg`;

export interface ProbeResult {
  home: string;
  tools: { bash: boolean; git: boolean; rg: boolean };
}

export interface ConnectOptions {
  /** The command line that runs the askpass role, as `[node, host.mjs, "askpass"]`. */
  askpassCommand: readonly string[];
  /**
   * Asks the user [prompt]'s question; null cancels the connect. For a `host_key` prompt any answer trusts the key.
   */
  prompt: (prompt: RemotePrompt) => Promise<string | null>;
  /** Kills `ssh` when it aborts; the connect then rejects with its reason. */
  signal?: AbortSignal;
}

/** The probe's output: the home folder, then a path for each tool found. */
export function parseProbe(output: string): ProbeResult | null {
  const [home, ...found] = output.split("\n").map((line) => line.trim());
  if (home === undefined || !home.startsWith("/")) return null;
  const tools = new Set(found.map((path) => posix.basename(path)));
  return { home, tools: { bash: tools.has("bash"), git: tools.has("git"), rg: tools.has("rg") } };
}

let bridges = 0;

/**
 * Connects [remote] interactively and leaves its master connection running: `ssh` asks about the host key, password
 * or passphrase through `ssh/askpass`, and each question goes to [options.prompt]. Runs [PROBE] and saves the home
 * folder it finds in `hosts.json`. A second password, or a second ask for the same key's passphrase, means the first
 * was wrong: the connect ends with `remote_auth_failed` rather than asking again.
 */
export async function connect(remote: Remote, options: ConnectOptions): Promise<ProbeResult> {
  const { paths, host } = remote;
  await writeSshConfig(paths);
  const askpassPath = join(paths.ssh, "askpass");
  await writeAtomically(askpassPath, `#!${join(paths.bin, "sh")}\nexec ${options.askpassCommand.map(quote).join(" ")} "$@"\n`, 0o700);

  const stop = new AbortController();
  const signal = options.signal === undefined ? stop.signal : AbortSignal.any([options.signal, stop.signal]);
  let passwordAsked = false;
  const passphrasesAsked = new Set<string>();
  const answer = async (text: string): Promise<string | null> => {
    const prompt = describePrompt(text);
    const repeated = prompt.kind === "password" ? passwordAsked : prompt.kind === "passphrase" && passphrasesAsked.has(text);
    if (repeated) {
      stop.abort(authFailed(host.name));
      return null;
    }
    if (prompt.kind === "password") passwordAsked = true;
    if (prompt.kind === "passphrase") passphrasesAsked.add(text);
    const reply = await options.prompt(prompt);
    if (reply === null) {
      stop.abort(connectCancelled());
      return null;
    }
    return prompt.kind === "host_key" ? "yes" : reply;
  };

  // In the SSH folder, whose path is short, so the socket's fits in `sun_path`.
  const socketPath = join(paths.ssh, `a${process.pid}-${++bridges}`);
  const env = sshEnvironment({ SSH_ASKPASS: askpassPath, SSH_ASKPASS_REQUIRE: "force", [ASKPASS_SOCKET_VARIABLE]: socketPath });
  const release = await acquireSession(host.id, signal);
  let exit: RunResult;
  try {
    const bridge = await startAskpassBridge(socketPath, answer);
    try {
      exit = await spawnSsh(paths, sshArgs(remote, PROBE, true), { signal }, env);
    } finally {
      await bridge.close();
    }
  } finally {
    release();
  }
  if (exit.code === 255) throw classifySshFailure(exit.stderr, { name: host.name, auth: host.auth, interactive: true }) ?? remoteFailed(host.name, lastLine(exit.stderr));
  const probe = parseProbe(exit.stdout.toString("utf8"));
  if (probe === null) throw remoteFailed(host.name, lastLine(exit.stderr) || "no home folder");
  if (!probe.tools.bash) throw bashMissing(host.name);
  await recordHome(paths, host.id, probe.home);
  return probe;
}
