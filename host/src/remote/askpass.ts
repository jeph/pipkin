/**
 * The askpass bridge of `remote_connect`. `ssh` runs `ssh/askpass` for every question it has (host key, password,
 * passphrase); that script runs the host bundle in its askpass role, which hands the question to the connecting host
 * over a Unix socket and prints the answer for `ssh`.
 */
import { createServer, connect as connectSocket, type Server, type Socket } from "node:net";
import { rm } from "node:fs/promises";
import { encodeLine, isObject, LineSplitter } from "../framing.js";

/** Names the bridge's socket in the environment `ssh` passes to its askpass program. */
export const ASKPASS_SOCKET_VARIABLE = "PIPKIN_ASKPASS_SOCKET";

export type PromptKind = "host_key" | "password" | "passphrase" | "other";

/** A question from `ssh`, as `pipkin_remote_prompt` carries it to the app. */
export interface RemotePrompt {
  kind: PromptKind;
  /** `ssh`'s own text. */
  text: string;
  /** For `host_key`: the key's type, such as `ED25519`. */
  keyType?: string;
  /** For `host_key`: the key's `SHA256:` fingerprint. */
  fingerprint?: string;
}

/** `ED25519 key fingerprint is SHA256:….`, which OpenSSH 10 writes `is: SHA256:…` without the full stop. */
const HOST_KEY = /^(\S+) key fingerprint is:? (SHA256:[A-Za-z0-9+/]+)/m;

/**
 * What kind of question `ssh`'s [text] asks. A host key question is known by its `continue connecting` alone, so a
 * rewording still gets the host key dialog (OpenSSH 10 already changed the fingerprint line); the key's type and
 * fingerprint come along when the wording still gives them.
 */
export function describePrompt(text: string): RemotePrompt {
  if (/continue connecting/.test(text)) {
    const hostKey = HOST_KEY.exec(text);
    if (hostKey?.[1] === undefined || hostKey[2] === undefined) return { kind: "host_key", text };
    return { kind: "host_key", text, keyType: hostKey[1], fingerprint: hostKey[2] };
  }
  if (/passphrase/i.test(text)) return { kind: "passphrase", text };
  if (/password/i.test(text)) return { kind: "password", text };
  return { kind: "other", text };
}

/**
 * The askpass role, run by `ssh` with its question in [prompt]: asks the bridge at `$PIPKIN_ASKPASS_SOCKET` and prints
 * the answer. Returns the exit status, 1 when the question was cancelled, which `ssh` takes for no answer.
 */
export async function askpass(prompt: string): Promise<number> {
  const path = process.env[ASKPASS_SOCKET_VARIABLE];
  if (path === undefined || path === "") return 1;
  // `none` only shows a notice, such as to touch a security key, and `ssh` kills its askpass once it's done.
  if (process.env.SSH_ASKPASS_PROMPT === "none") return 0;
  const answer = await ask(path, prompt, process.env.SSH_ASKPASS_PROMPT === "confirm");
  if (answer === null) return 1;
  const { promise, resolve } = Promise.withResolvers<void>();
  process.stdout.write(`${answer}\n`, () => resolve());
  await promise;
  return 0;
}

/** [line]'s JSON value; undefined when it isn't JSON, which only a broken peer sends. */
function parseLine(line: string): unknown {
  try {
    return JSON.parse(line);
  } catch {
    return undefined;
  }
}

/** The bridge's answer to [prompt], or null when it cancelled or went away. */
async function ask(path: string, prompt: string, confirm: boolean): Promise<string | null> {
  const { promise, resolve } = Promise.withResolvers<string | null>();
  const socket = connectSocket(path);
  const splitter = new LineSplitter((line) => {
    const reply = parseLine(line);
    resolve(isObject(reply) && typeof reply.answer === "string" ? reply.answer : null);
    socket.destroy();
  });
  socket.on("data", (chunk: Buffer) => {
    splitter.push(chunk);
  });
  socket.on("error", () => resolve(null));
  socket.on("close", () => resolve(null));
  socket.write(encodeLine({ prompt, confirm }));
  return promise;
}

export interface AskpassBridge {
  close(): Promise<void>;
}

/**
 * Listens on Unix socket [path] (kept short, as `sun_path` is) for askpass questions and answers each with
 * [answer]'s reply; null makes the askpass exit without an answer.
 */
export async function startAskpassBridge(path: string, answer: (prompt: string, confirm: boolean) => Promise<string | null>): Promise<AskpassBridge> {
  const sockets = new Set<Socket>();
  const server: Server = createServer((socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    socket.on("error", () => socket.destroy());
    const splitter = new LineSplitter((line) => {
      const question = parseLine(line);
      if (!isObject(question) || typeof question.prompt !== "string") {
        socket.destroy();
        return;
      }
      void answer(question.prompt, question.confirm === true).then(
        (reply) => socket.end(encodeLine({ answer: reply })),
        () => socket.destroy(),
      );
    });
    socket.on("data", (chunk: Buffer) => {
      splitter.push(chunk);
    });
  });
  await rm(path, { force: true });
  const { promise, resolve, reject } = Promise.withResolvers<void>();
  server.once("error", reject);
  server.listen(path, resolve);
  await promise;
  return {
    async close() {
      for (const socket of sockets) socket.destroy();
      const closed = Promise.withResolvers<void>();
      server.close(() => closed.resolve());
      await closed.promise;
      await rm(path, { force: true });
    },
  };
}
