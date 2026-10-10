import type { Socket } from "node:net";
import { encodeLine, isObject, LineSplitter, LineTooLong, type JsonObject } from "./framing.js";

/**
 * The connect-back socket to the app. Lines are parsed until the mode either listens for
 * further messages (login) or detaches the socket into a raw byte pipe (rpc).
 */
export class Connection {
  /** Aborted when the app closes the socket; modes pass it to every in-flight operation. */
  readonly abort = new AbortController();
  private readonly splitter: LineSplitter;
  private readonly queue: JsonObject[] = [];
  private listener: ((message: JsonObject) => void) | undefined;
  private rawAfterFirst = false;
  private detached = false;
  private readonly onData = (chunk: Buffer): void => {
    try {
      this.splitter.push(chunk);
    } catch (error) {
      if (!(error instanceof LineTooLong)) throw error;
      this.socket.destroy();
      return;
    }
    if (this.detached) {
      this.socket.off("data", this.onData);
      this.socket.pause();
    }
    this.flush();
  };

  /** `socket` must allow half-open, so a result can still be written after the app's FIN. */
  constructor(readonly socket: Socket) {
    this.splitter = new LineSplitter((line) => {
      this.receive(line);
    });
    const hangUp = (): void => {
      this.abort.abort(new Error("Pipkin closed the connection"));
    };
    socket.on("data", this.onData);
    socket.on("error", () => {
      socket.destroy();
    });
    socket.on("end", hangUp);
    socket.on("close", hangUp);
  }

  send(message: JsonObject): void {
    if (this.socket.writable) this.socket.write(encodeLine(message));
  }

  /**
   * Resolves with the first message. With `raw`, parsing stops right after it and the socket
   * is paused, so every later byte (including any already received) is left for `leftover`.
   */
  firstMessage(raw: boolean, timeoutMs: number): Promise<JsonObject | "timeout" | "closed"> {
    this.rawAfterFirst = raw;
    const { promise, resolve } = Promise.withResolvers<JsonObject | "timeout" | "closed">();
    const onAbort = (): void => {
      clearTimeout(timer);
      resolve("closed");
    };
    const timer = setTimeout(() => {
      this.abort.signal.removeEventListener("abort", onAbort);
      resolve("timeout");
    }, timeoutMs);
    this.abort.signal.addEventListener("abort", onAbort, { once: true });
    this.listen((message) => {
      clearTimeout(timer);
      this.abort.signal.removeEventListener("abort", onAbort);
      this.listener = undefined;
      resolve(message);
    });
    return promise;
  }

  /** Delivers queued and future messages to `listener`. */
  listen(listener: (message: JsonObject) => void): void {
    this.listener = listener;
    this.flush();
  }

  /** Bytes received after the first message when it was read in raw mode. */
  get leftover(): Buffer {
    return this.splitter.leftover;
  }

  /** Flushes pending writes, closes the socket and exits the process. */
  finish(code = 0): void {
    if (!this.socket.writable) process.exit(code);
    this.socket.end(() => process.exit(code));
  }

  private receive(line: string): void {
    if (line.length === 0) return;
    let message: unknown;
    try {
      message = JSON.parse(line);
    } catch {
      process.stderr.write("pipkin-host: ignoring malformed line\n");
      return;
    }
    if (!isObject(message)) return;
    this.queue.push(message);
    if (this.rawAfterFirst) {
      this.detached = true;
      this.splitter.stop();
    }
  }

  private flush(): void {
    while (this.listener !== undefined && this.queue.length > 0) {
      const message = this.queue.shift();
      if (message !== undefined) this.listener(message);
    }
  }
}
