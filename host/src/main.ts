import { connect } from "node:net";
import { Connection } from "./connection.js";
import { HostError, installedPiVersion, readHostEnv } from "./env.js";
import { isObject, type JsonObject } from "./framing.js";
import type { ModeContext } from "./mode.js";
import { authStatus } from "./modes/auth-status.js";
import { browse } from "./modes/browse.js";
import { deleteSession } from "./modes/delete-session.js";
import { ensure } from "./modes/ensure.js";
import { login } from "./modes/login.js";
import { logout } from "./modes/logout.js";
import { providerModels } from "./modes/provider-models.js";
import { providerRemove } from "./modes/provider-remove.js";
import { providerSave } from "./modes/provider-save.js";
import { rpc } from "./modes/rpc.js";
import { saveAttachments } from "./modes/save-attachments.js";
import { sessions } from "./modes/sessions.js";

const PROTOCOL = 1;
const REQUEST_TIMEOUT_MS = 10_000;
/** After the app hangs up, an aborted operation gets this long to unwind before the process exits. */
const ABORT_GRACE_MS = 5000;

const EXIT_USAGE = 64;
const EXIT_NO_REQUEST = 2;

function failure(error: unknown): JsonObject {
  const message = error instanceof Error ? error.message : String(error);
  const code = error instanceof HostError ? error.code : "failed";
  return { type: "pipkin_result", ok: false, error: message, code };
}

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
    process.stderr.write("usage: PIPKIN_TOKEN=… PIPKIN_ROOT=… PI_CODING_AGENT_DIR=… host.mjs <mode> <port>\n");
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
    case "ensure":
      return respond(ctx, ensure);
    case "sessions":
      return respond(ctx, sessions);
    case "delete_session":
      return respond(ctx, deleteSession);
    case "browse":
      return respond(ctx, browse);
    case "save_attachments":
      return respond(ctx, saveAttachments);
    case "auth_status":
      return respond(ctx, authStatus);
    case "login":
      return respond(ctx, login);
    case "logout":
      return respond(ctx, logout);
    case "provider_models":
      return respond(ctx, providerModels);
    case "provider_save":
      return respond(ctx, providerSave);
    case "provider_remove":
      return respond(ctx, providerRemove);
    default:
      conn.send({ type: "pipkin_result", ok: false, error: `Unknown mode ${mode}`, code: "unknown_mode" });
      conn.finish();
  }
}

await main();
