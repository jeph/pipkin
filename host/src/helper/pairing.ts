/**
 * `node helper.mjs pair`: fetches the pending pairing key from Pipkin's setup endpoint and stores it as `key`.
 * The setup command carries the token T; the body is encrypted under pk = HMAC(T, "pipkin-pair-key") so the key
 * never appears in the terminal or the command itself.
 */
import { createDecipheriv, createHmac } from "node:crypto";
import { type HelperFiles, writeFileAtomic } from "./files.js";

/** Exit code when the setup session is gone (HTTP 410); the installer decides whether an existing key is enough. */
export const PAIR_GONE = 10;

const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const KEY_BYTES = 32;

export function pairingKey(token: Buffer): Buffer {
  return createHmac("sha256", token).update("pipkin-pair-key", "ascii").digest();
}

/** Decrypts the `/key` response body (hex of nonce || ciphertext || tag) into the 32-byte pairing key. */
export function decryptPairingBody(bodyHex: string, token: Buffer, sid: string): Buffer {
  const hex = bodyHex.trim();
  if (!/^[0-9a-fA-F]+$/.test(hex) || hex.length !== 2 * (NONCE_BYTES + KEY_BYTES + TAG_BYTES)) {
    throw new Error("Pipkin sent a malformed key.");
  }
  const body = Buffer.from(hex, "hex");
  const decipher = createDecipheriv("aes-256-gcm", pairingKey(token), body.subarray(0, NONCE_BYTES));
  decipher.setAAD(Buffer.from(`pipkin-pair|${sid}`, "utf8"));
  decipher.setAuthTag(body.subarray(body.length - TAG_BYTES));
  try {
    return Buffer.concat([decipher.update(body.subarray(NONCE_BYTES, body.length - TAG_BYTES)), decipher.final()]);
  } catch {
    throw new Error("The key from Pipkin didn't match this setup command.");
  }
}

export async function pair(env: NodeJS.ProcessEnv, files: HelperFiles): Promise<number> {
  try {
    const url = env.PIPKIN_URL?.replace(/\/+$/, "");
    const tokenHex = env.PIPKIN_TOKEN ?? "";
    if (!url || !/^[0-9a-fA-F]{64}$/.test(tokenHex)) throw new Error("Paste the setup command from Pipkin.");
    const sid = url.slice(url.lastIndexOf("/") + 1);
    let response: Response;
    try {
      response = await fetch(`${url}/key`, { signal: AbortSignal.timeout(15_000) });
    } catch {
      throw new Error("Couldn't reach Pipkin. Keep Pipkin open and paste the command again.");
    }
    if (response.status === 410) return PAIR_GONE;
    if (response.status !== 200) throw new Error(`Pipkin answered ${response.status} when asked for the key.`);
    const key = decryptPairingBody(await response.text(), Buffer.from(tokenHex, "hex"), sid);
    files.ensureDir();
    writeFileAtomic(files.key, `${key.toString("hex")}\n`);
    return 0;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`Pipkin: ${message}\n`);
    return 1;
  }
}
