import type { JsonObject } from "../framing.js";
import type { ModeContext } from "../mode.js";
import { stringParam } from "../params.js";
import { connect } from "../remote/connect.js";
import { findHost } from "../remote/hosts.js";
import { remotePaths } from "../remote/paths.js";

/**
 * Connects host `hostId`, leaving its master connection for every later call. Each question `ssh` asks goes to the
 * app as `pipkin_remote_prompt { id, kind, text, keyType?, fingerprint? }`, answered by
 * `pipkin_remote_answer { id, answer }`; a null answer, or the app hanging up, stops `ssh`. With `unattended: true`,
 * for the app answering with a saved password, `ssh` refuses an unknown or changed host key instead of asking.
 * Answers `{ home, tools: { bash, git, rg } }`.
 */
export async function remoteConnect(ctx: ModeContext): Promise<JsonObject> {
  const paths = remotePaths(ctx.env.configDir, ctx.env.prefix, ctx.env.home);
  const host = await findHost(paths, stringParam(ctx.params, "hostId"));
  const entry = process.argv[1];
  if (entry === undefined) throw new Error("The host bundle's path is unknown");

  const pending = new Map<number, (answer: string | null) => void>();
  let nextId = 1;
  ctx.conn.listen((message) => {
    const resolve = typeof message.id === "number" ? pending.get(message.id) : undefined;
    if (message.type !== "pipkin_remote_answer" || resolve === undefined) return;
    pending.delete(Number(message.id));
    resolve(typeof message.answer === "string" ? message.answer : null);
  });
  ctx.signal.addEventListener(
    "abort",
    () => {
      for (const resolve of pending.values()) resolve(null);
      pending.clear();
    },
    { once: true },
  );

  const probe = await connect(
    { paths, host },
    {
      askpassCommand: [process.execPath, entry, "askpass"],
      signal: ctx.signal,
      unattended: ctx.params.unattended === true,
      prompt(prompt) {
        if (ctx.signal.aborted) return Promise.resolve(null);
        const id = nextId++;
        const { promise, resolve } = Promise.withResolvers<string | null>();
        pending.set(id, resolve);
        ctx.conn.send({ type: "pipkin_remote_prompt", id, ...prompt });
        return promise;
      },
    },
  );
  return { home: probe.home, tools: probe.tools };
}
