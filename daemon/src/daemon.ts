/**
 * Pipkin agent supervisor. Launched by the bootstrap in Termux as
 * `node pipkin-daemon.mjs <port> <installId>` with TLS and bearer credentials in PIPKIN_CREDENTIALS.
 *
 * Exit codes shared with TermuxResultReceiver: 70 Termux environment, 78 port in use,
 * 79 lock held by a live daemon, 80 credentials missing or malformed.
 */
import fs from "node:fs";
import path from "node:path";
import { parseCredentials, UUID, type Credentials } from "./auth.js";
import { createSecureContext } from "node:tls";
import { PI_ARGS, PI_PACKAGE, PI_VERSION } from "./pi.js";
import { Daemon, StartupFailure } from "./server.js";

const EXIT_ENVIRONMENT = 70;
const EXIT_CREDENTIALS = 80;
const IDLE_EXIT_MS = 15 * 60_000;
const IDLE_POLL_MS = 30_000;
const TRACK_MS = 2000;
const INHERITED = [
  "PREFIX",
  "LD_PRELOAD",
  "LD_LIBRARY_PATH",
  "ANDROID_ROOT",
  "ANDROID_DATA",
  "ANDROID_RUNTIME_ROOT",
  "ANDROID_TZDATA_ROOT",
  "ANDROID_I18N_ROOT",
  "EXTERNAL_STORAGE",
  "TERMUX_VERSION",
  "TERMUX_APK_RELEASE",
  "TERMUX_APP_PID",
  "TERMUX__PREFIX",
  "TERMUX__ROOTFS",
];

function fail(code: number, message: string): never {
  process.stderr.write(`pipkin-daemon: ${message}\n`);
  process.exit(code);
}

const credentialsText = process.env.PIPKIN_CREDENTIALS;
delete process.env.PIPKIN_CREDENTIALS;
let credentials: Credentials;
try {
  credentials = parseCredentials(credentialsText);
  // Reject malformed PEM or mismatched keys as a provisioning error, before startup.
  createSecureContext({ cert: credentials.certificate, key: credentials.privateKey, minVersion: "TLSv1.2" });
} catch {
  fail(EXIT_CREDENTIALS, "missing or malformed daemon credentials");
}

const [portText, installId] = process.argv.slice(2);
const port = Number(portText);
if (!Number.isSafeInteger(port) || port < 1024 || port > 65535) fail(EXIT_ENVIRONMENT, "invalid port");
if (installId === undefined || !UUID.test(installId)) fail(EXIT_ENVIRONMENT, "invalid install id");

const version = process.versions.node.split(".").map(Number);
const nodeOk = (version[0] ?? 0) > 22 || ((version[0] ?? 0) === 22 && (version[1] ?? 0) >= 19);
if (process.platform !== "android" || !nodeOk) fail(EXIT_ENVIRONMENT, "Termux Node 22.19.0 or newer is required");
if (typeof process.env.PREFIX !== "string" || typeof process.env.HOME !== "string") fail(EXIT_ENVIRONMENT, "missing Termux environment");
const prefix = fs.realpathSync(process.env.PREFIX);
const termuxHome = fs.realpathSync(process.env.HOME);
if (!/^\/data\/(?:data|user\/\d+)\/com\.termux\/files\/usr$/.test(prefix)) fail(EXIT_ENVIRONMENT, "official Termux prefix is required");
if (termuxHome !== path.join(path.dirname(prefix), "home") || fs.realpathSync(process.execPath) !== fs.realpathSync(path.join(prefix, "bin/node"))) {
  fail(EXIT_ENVIRONMENT, "official Termux Node and home are required");
}
const root = path.join(termuxHome, ".local/share/pipkin", installId);

// Only platform/Termux execution support is inherited. In particular no provider secrets,
// Node options, shell startup files, user PATH entries or user package-manager config.
const environment: Record<string, string> = {};
for (const name of INHERITED) {
  const value = process.env[name];
  if (value !== undefined) environment[name] = value;
}
Object.assign(environment, {
  PREFIX: prefix,
  PATH: `${prefix}/bin:/system/bin:/system/xbin`,
  LANG: "C.UTF-8",
  TMPDIR: path.join(root, "tmp"),
  HOME: path.join(root, "home"),
  SHELL: `${prefix}/bin/bash`,
  TERM: "dumb",
  NO_COLOR: "1",
});

const daemon = new Daemon({
  host: "127.0.0.1",
  port,
  installId,
  credentials,
  root,
  prefix,
  shell: `${prefix}/bin/bash`,
  uid: process.getuid?.() ?? -1,
  environment,
  npm: `${prefix}/bin/npm`,
  pi: {
    command: process.execPath,
    args: [path.join(root, "runtime/pi/node_modules", PI_PACKAGE, "dist/bundle/rpc-entry.js"), ...PI_ARGS],
  },
  piVersion: PI_VERSION,
  idleExitMs: IDLE_EXIT_MS,
  idlePollMs: IDLE_POLL_MS,
  trackMs: TRACK_MS,
});

async function exit(code: number): Promise<never> {
  await daemon.shutdown(code);
  process.exit(daemon.exitStatus);
}

for (const signal of ["SIGTERM", "SIGINT", "SIGHUP"] as const) {
  process.on(signal, () => {
    void exit(0);
  });
}
process.on("uncaughtException", () => {
  void exit(1);
});
process.on("unhandledRejection", () => {
  void exit(1);
});

daemon.start().catch((error: unknown) => {
  if (error instanceof StartupFailure) fail(error.code, error.message);
  fail(1, error instanceof Error ? error.message : "startup failed");
});
