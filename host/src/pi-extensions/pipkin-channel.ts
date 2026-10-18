/**
 * The extensions' end of Pipkin's channel (see `../channel.ts`): the socket the host starts Pi with on fd CHANNEL_FD.
 * Every bundled extension shares one, kept on `globalThis`, since each is bundled with its own copy of this module and
 * Pi loads an extension again for each session and `/reload`.
 */
import { fstatSync } from "node:fs";
import { Socket } from "node:net";
import {
  CHANNEL_FD,
  type ChannelMessage,
  ChannelReader,
  encodeChannelMessage,
  HELLO_TOPIC,
} from "../channel.js";

export interface PipkinChannel {
  /** Sends [data] on [topic] to the app; dropped when Pi doesn't run under Pipkin's host. */
  send(topic: string, data: unknown): void;
  /** Calls [handler] with each message the app sends on [topic], until the returned function is called. */
  on(topic: string, handler: (data: unknown) => void): () => void;
}

/** Messages sent before the host's hello arrived, which go out with it; more than this and the oldest go. */
const MAX_EARLY = 64;

/** A channel on [stream], which may only send once the host's hello arrived on it. */
export function openChannel(stream: Pick<Socket, "on" | "write">): PipkinChannel {
  const handlers = new Map<string, Set<(data: unknown) => void>>();
  let early: string[] | null = [];
  const reader = new ChannelReader(
    (message: ChannelMessage) => {
      if (message.topic === HELLO_TOPIC) {
        for (const line of early ?? []) stream.write(line);
        early = null;
        return;
      }
      for (const handler of handlers.get(message.topic) ?? []) handler(message.data);
    },
    () => undefined,
  );
  stream.on("data", (chunk: Buffer) => reader.push(chunk));
  stream.on("error", () => undefined);
  return {
    send(topic, data) {
      const line = encodeChannelMessage({ topic, data });
      if (early === null) {
        stream.write(line);
        return;
      }
      early.push(line);
      if (early.length > MAX_EARLY) early.shift();
    },
    on(topic, handler) {
      const set = handlers.get(topic) ?? new Set();
      handlers.set(topic, set);
      set.add(handler);
      return () => set.delete(handler);
    },
  };
}

const INERT: PipkinChannel = { send: () => undefined, on: () => () => undefined };

/**
 * Whether fd CHANNEL_FD can be the host's socket: open, a socket, and not Node's own IPC channel, which a Pi started by
 * `fork()` has there. Anything else on it, under a Pi the host didn't start, is left alone.
 */
function hostSocketPresent(): boolean {
  if (process.channel !== undefined) return false;
  try {
    return fstatSync(CHANNEL_FD).isSocket();
  } catch {
    return false;
  }
}

const SHARED = Symbol.for("pipkin.channel");

/** This Pi's channel to the app, opened by the first extension that asks. */
export function pipkinChannel(): PipkinChannel {
  const global = globalThis as { [SHARED]?: PipkinChannel };
  if (global[SHARED]) return global[SHARED];
  let channel = INERT;
  if (hostSocketPresent()) {
    const socket = new Socket({ fd: CHANNEL_FD, readable: true, writable: true });
    // Pi exits once its stdin ends; the channel mustn't keep it running.
    socket.unref();
    channel = openChannel(socket);
  }
  global[SHARED] = channel;
  return channel;
}
