import { connect } from "node:net";
import { Connection } from "./connection.js";
import { installedPiVersion, grantedSharedStorage, readHostEnv } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";
import { failure, type ModeContext } from "./mode.js";
import { createBackup, restoreBackup } from "./modes/backups.js";
import { browse } from "./modes/browse.js";
import { ensure } from "./modes/ensure.js";
import { files } from "./modes/files.js";
import { git } from "./modes/git.js";
import { login } from "./modes/login.js";
import { packageInstall } from "./modes/package-install.js";
import { rpc } from "./modes/rpc.js";
import { terminal } from "./modes/terminal.js";
import { updateExtensions } from "./modes/update-extensions.js";
import { utility } from "./modes/utility.js";

const PROTOCOL = 1;
const REQUEST_TIMEOUT_MS = 10_000;
/** After the app hangs up, an aborted operation gets this long to unwind before the process exits. */
const ABORT_GRACE_MS = 5000;

const EXIT_USAGE = 64;
const EXIT_NO_REQUEST = 2;

/** Runs a non-rpc mode and reports its outcome as the single `pipkin_result` line. */
async function respond(ctx: ModeContext, run: (ctx: ModeContext) => Promise<JsonObject>): Promise<void> {
  ctx.signal.addEventListener(
    "abort",
    () => {
      setTimeout(() => process.exit(1), ABORT_GRACE_MS).unref();
    },
    { once: true },
  );
  try {
    const data = await run(ctx);
    ctx.conn.send({ type: "pipkin_result", ok: true, data });
  } catch (error) {
    if (ctx.signal.aborted) process.exit(1);
    ctx.conn.send(failure(error));
  }
  ctx.conn.finish();
}

async function main(): Promise<void> {
  const [mode, portArg] = process.argv.slice(2);
  const env = readHostEnv();
  const port = Number(portArg);
  if (mode === undefined || env === undefined || !Number.isInteger(port) || port <= 0 || port > 65_535) {
    process.stderr.write("usage: PIPKIN_TOKEN=… PIPKIN_ROOT=… PI_CODING_AGENT_DIR=… PREFIX=… host.mjs <mode> <port>\n");
    process.exit(EXIT_USAGE);
  }

  const socket = connect({ host: "127.0.0.1", port, allowHalfOpen: true });
  const conn = new Connection(socket);
  conn.send({
    type: "pipkin_hello",
    protocol: PROTOCOL,
    token: env.token,
    mode,
    piVersion: await installedPiVersion(env.root),
    nodeVersion: process.versions.node,
    configDir: env.configDir,
    home: env.home,
    sharedStorage: (await grantedSharedStorage(env)) !== null,
  });

  const request = await conn.firstMessage(mode === "rpc", REQUEST_TIMEOUT_MS);
  if (request === "timeout") process.exit(EXIT_NO_REQUEST);
  if (request === "closed") process.exit(1);
  if (request.type !== "pipkin_request" || !isObject(request.params)) {
    conn.send({ type: "pipkin_result", ok: false, error: "Expected a pipkin_request", code: "bad_request" });
    conn.finish();
    return;
  }

  const ctx: ModeContext = { env, params: request.params, conn, signal: conn.abort.signal };
  switch (mode) {
    case "rpc":
      try {
        await rpc(ctx);
      } catch (error) {
        conn.send(failure(error));
        conn.finish();
      }
      return;
    case "terminal":
      try {
        await terminal(ctx);
      } catch (error) {
        conn.send(failure(error));
        conn.finish();
      }
      return;
    case "ensure":
      return respond(ctx, ensure);
    case "files":
      return respond(ctx, files);
    case "git":
      return respond(ctx, git);
    case "utility":
      return respond(ctx, utility);
    case "browse":
      return respond(ctx, browse);
    case "login":
      return respond(ctx, login);
    case "package_install":
      return respond(ctx, packageInstall);
    case "backup_create":
      return respond(ctx, createBackup);
    case "backup_restore":
      return respond(ctx, restoreBackup);
    case "update_extensions":
      return respond(ctx, updateExtensions);
    case "hello":
      // Answers with nothing: the hello is the point, so the app can re-read what it reports after a settings change.
      return respond(ctx, async () => ({}));
    default:
      conn.send({ type: "pipkin_result", ok: false, error: `Unknown mode ${mode}`, code: "unknown_mode" });
      conn.finish();
  }
}

await main();
