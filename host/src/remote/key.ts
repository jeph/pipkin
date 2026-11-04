import { execFile } from "node:child_process";
import { readFile, rename, rm } from "node:fs/promises";
import os from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { commandFailed } from "../modes/ensure.js";
import { errorCode, sshMissing } from "./errors.js";
import { ensureSshDir } from "./hosts.js";
import { keyFile, type RemotePaths } from "./paths.js";

const run = promisify(execFile);

/** The phone's model (`Pixel 9`), which names Pipkin's key in the server's `authorized_keys`; the host name off Android. */
async function deviceModel(): Promise<string> {
  try {
    const { stdout } = await run("getprop", ["ro.product.model"], { timeout: 5000 });
    const model = stdout.trim();
    if (model !== "") return model;
  } catch {
    // Not on Android.
  }
  return os.hostname();
}

/** Pipkin's public key line, or null before Pipkin made its key. */
export async function publicKey(paths: RemotePaths): Promise<string | null> {
  const file = keyFile(paths);
  try {
    return (await readFile(`${file}.pub`, "utf8")).trim();
  } catch (error) {
    if (errorCode(error) !== "ENOENT") throw error;
  }
  try {
    // The private key alone, as when the public half was deleted: derive the line again.
    const { stdout } = await run(join(paths.bin, "ssh-keygen"), ["-y", "-f", file]);
    return stdout.trim();
  } catch {
    return null;
  }
}

let making: Promise<string> | undefined;

/**
 * Pipkin's public key line, making the key first if needed: ed25519, no passphrase, mode 600, commented
 * `pipkin@<device model>`. It's made under a temporary name and moved in, private half first, so a key that's there is
 * always whole.
 */
export function ensureKey(paths: RemotePaths): Promise<string> {
  making ??= makeKey(paths).finally(() => {
    making = undefined;
  });
  return making;
}

async function makeKey(paths: RemotePaths): Promise<string> {
  const existing = await publicKey(paths);
  if (existing !== null) return existing;
  await ensureSshDir(paths);
  const file = keyFile(paths);
  const temporary = `${file}.${process.pid}.tmp`;
  await rm(temporary, { force: true });
  await rm(`${temporary}.pub`, { force: true });
  const args = ["-q", "-t", "ed25519", "-N", "", "-C", `pipkin@${await deviceModel()}`, "-f", temporary];
  try {
    await run(join(paths.bin, "ssh-keygen"), args);
  } catch (error) {
    const code = errorCode(error);
    if (code === "ENOENT") throw sshMissing();
    throw commandFailed("remote_key_failed", "ssh-keygen", typeof code === "number" ? code : null);
  }
  // Private half first: a crash between the two leaves a lone private key, whose public line `publicKey` derives,
  // never a public line without the key that signs for it.
  await rename(temporary, file);
  await rename(`${temporary}.pub`, `${file}.pub`);
  return (await readFile(`${file}.pub`, "utf8")).trim();
}
