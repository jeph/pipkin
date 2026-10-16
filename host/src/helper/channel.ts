import type { Socket } from "node:net";
import type { JsonObject } from "../framing.js";
import { FrameOpener, FrameSealer } from "./protocol.js";

/**
 * One authenticated connection after the handshake: frames out under `sendKey`, frames in under `receiveKey`. A frame
 * that fails to parse or authenticate destroys the socket before any later frame is looked at.
 */
export class HelperChannel {
  private readonly sealer: FrameSealer;
  private readonly opener: FrameOpener;

  constructor(
    readonly socket: Socket,
    sendKey: Buffer,
    receiveKey: Buffer,
    private readonly onMessage: (message: JsonObject) => void,
  ) {
    this.sealer = new FrameSealer(sendKey);
    this.opener = new FrameOpener(receiveKey);
    socket.removeAllListeners("data");
    socket.on("data", (chunk: Buffer) => this.receive(chunk));
  }

  /** Feeds bytes that arrived together with the end of the handshake. */
  receive(chunk: Buffer): void {
    if (chunk.length === 0 || this.socket.destroyed) return;
    let messages: JsonObject[];
    try {
      messages = this.opener.push(chunk);
    } catch {
      this.socket.destroy();
      return;
    }
    for (const message of messages) {
      if (this.socket.destroyed) return;
      this.onMessage(message);
    }
  }

  get open(): boolean {
    return !this.socket.destroyed && this.socket.writable;
  }

  send(message: JsonObject, onWritten?: () => void): void {
    if (!this.open) return;
    this.socket.write(this.sealer.seal(message), onWritten);
  }
}
