import { spawn, type ChildProcess } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdir, mkdtemp, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { createServer, type Socket } from "node:net";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { inject } from "vitest";
import { encodeLine, isObject, LineSplitter, type JsonObject } from "../src/framing.js";

export const PI_VERSION = "1.3.0";
/** The runtime `pi/package.json` that `ensure` would have installed: Pi alone, no bundled extensions. */
export const PACKAGE_JSON = runtimeManifest([]);
const FAKE_PI = path.join(import.meta.dirname, "fixtures/fake-pi.mjs");
const REAL_PI = path.join(import.meta.dirname, "../node_modules/@earendil-works/pi-coding-agent");
const PROVIDER_CREDENTIAL = /_(API_KEY|TOKEN)$|^(AWS|GOOGLE|AZURE)_/;

export interface Dirs {
  base: string;
  root: string;
  configDir: string;
  home: string;
  /** Stands in for Termux's `$PREFIX`. */
  prefix: string;
}

export async function makeDirs(): Promise<Dirs> {
  const base = await realpath(await mkdtemp(path.join(os.tmpdir(), "pipkin-host-test-")));
  const dirs = {
    base,
    root: path.join(base, "root"),
    configDir: path.join(base, "config"),
    home: path.join(base, "home"),
    prefix: path.join(base, "prefix"),
  };
  await Promise.all([dirs.root, dirs.configDir, dirs.home, dirs.prefix].map((dir) => mkdir(dir, { recursive: true })));
  return dirs;
}

export async function removeDirs(dirs: Dirs): Promise<void> {
  await rm(dirs.base, { recursive: true, force: true });
}

function piDir(dirs: Dirs): string {
  return path.join(dirs.root, "pi/node_modules/@earendil-works/pi-coding-agent");
}

/** A runtime `pi/package.json` pinning Pi and the given bundled extension packages. */
export function runtimeManifest(extensions: string[]): string {
  const dependencies = Object.fromEntries([["@earendil-works/pi-coding-agent", PI_VERSION], ...extensions.map((name) => [name, "1.0.0"])]);
  return JSON.stringify({ name: "pipkin-pi-runtime", private: true, dependencies });
}

/** Writes the runtime `pi/package.json` the way `ensure` leaves it after an install. */
export async function writeRuntimeManifest(dirs: Dirs, packageJson = PACKAGE_JSON): Promise<void> {
  await writeFile(path.join(dirs.root, "pi/package.json"), packageJson);
}

/**
 * Installs fake-pi as the pinned Pi package, with Pi's real `bin.pi` layout. Its SDK entry offers Pi's real
 * `SettingsManager`, which `rpc` uses to default the steering mode.
 */
export async function installFakePi(dirs: Dirs, version = PI_VERSION): Promise<void> {
  const dir = piDir(dirs);
  await mkdir(path.join(dir, "dist/bundle"), { recursive: true });
  await writeFile(path.join(dir, "package.json"), JSON.stringify({ name: "@earendil-works/pi-coding-agent", version, bin: { pi: "dist/bundle/cli.js" } }));
  await writeFile(path.join(dir, "dist/bundle/cli.js"), `import ${JSON.stringify(FAKE_PI)};\n`);
  const realSdk = pathToFileURL(path.join(await realpath(REAL_PI), "dist/index.js")).href;
  await writeFile(path.join(dir, "dist/index.js"), `export { SettingsManager } from ${JSON.stringify(realSdk)};\n`);
  await writeRuntimeManifest(dirs);
}

/** Links the real Pi devDependency in as the pinned install, for modes that use the SDK. */
export async function installRealPi(dirs: Dirs): Promise<void> {
  const dir = piDir(dirs);
  await mkdir(path.dirname(dir), { recursive: true });
  await symlink(await realpath(REAL_PI), dir);
  await writeRuntimeManifest(dirs);
}

export class HostRun {
  readonly messages: JsonObject[] = [];
  private waiters: (() => void)[] = [];
  private ended = false;

  constructor(
    readonly socket: Socket,
    readonly child: ChildProcess,
    readonly exited: Promise<number | null>,
  ) {
    const splitter = new LineSplitter((line) => {
      const value: unknown = JSON.parse(line);
      if (isObject(value)) this.messages.push(value);
      this.wake();
    });
    socket.on("data", (chunk: Buffer) => {
      splitter.push(chunk);
    });
    socket.on("close", () => {
      this.ended = true;
      this.wake();
    });
  }

  /** Resolves with the next message, or undefined once the host closed the socket. */
  async next(): Promise<JsonObject | undefined> {
    for (;;) {
      const message = this.messages.shift();
      if (message !== undefined) return message;
      if (this.ended) return undefined;
      const { promise, resolve } = Promise.withResolvers<void>();
      this.waiters.push(resolve);
      await promise;
    }
  }

  send(message: JsonObject): void {
    this.socket.write(encodeLine(message));
  }

  private wake(): void {
    const waiters = this.waiters;
    this.waiters = [];
    for (const waiter of waiters) waiter();
  }
}

/** Starts host.mjs the way the Termux bootstrap does and accepts its connect-back. */
export async function startHost(
  mode: string,
  dirs: Dirs,
  extraEnv: Record<string, string> = {},
): Promise<{ run: HostRun; token: string }> {
  const token = randomBytes(32).toString("hex");
  const server = createServer();
  const listening = Promise.withResolvers<void>();
  server.listen(0, "127.0.0.1", listening.resolve);
  await listening.promise;
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("No server address");
  const accepted = Promise.withResolvers<Socket>();
  server.once("connection", accepted.resolve);

  const child = spawn(process.execPath, [inject("hostBundle"), mode, String(address.port)], {
    env: {
      // Provider credentials in the developer's environment would change which models Pi offers.
      ...Object.fromEntries(Object.entries(process.env).filter(([key]) => !PROVIDER_CREDENTIAL.test(key))),
      PIPKIN_TOKEN: token,
      PIPKIN_ROOT: dirs.root,
      PI_CODING_AGENT_DIR: dirs.configDir,
      HOME: dirs.home,
      PREFIX: dirs.prefix,
      ...extraEnv,
    },
    stdio: ["ignore", "inherit", "inherit"],
  });
  const exited = Promise.withResolvers<number | null>();
  child.on("exit", exited.resolve);
  const socket = await accepted.promise;
  server.close();
  return { run: new HostRun(socket, child, exited.promise), token };
}

/** Runs a non-rpc mode to completion and returns its `pipkin_result`; progress lines go to `progress`. */
export async function runMode(
  mode: string,
  dirs: Dirs,
  params: JsonObject,
  extraEnv: Record<string, string> = {},
  progress: string[] = [],
): Promise<JsonObject> {
  const { run } = await startHost(mode, dirs, extraEnv);
  await run.next();
  run.send({ type: "pipkin_request", params });
  for (;;) {
    const message = await run.next();
    if (message === undefined) throw new Error(`${mode} closed without a result`);
    if (message.type === "pipkin_progress") progress.push(String(message.line));
    if (message.type === "pipkin_result") {
      await run.exited;
      return message;
    }
  }
}
