/**
 * A remote chat as the app runs it: the production host bundle in `rpc` mode, the real Pi, and the extension bundles
 * `ensure` installs, so what `pipkin-remote` tells the app crosses Pipkin's channel as it does on the phone.
 */
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, inject, it } from "vitest";
import { isObject, type JsonObject } from "../src/framing.js";
import { updateHosts } from "../src/remote/hosts.js";
import { installRealPi, PACKAGE_JSON, PI_VERSION, startHost } from "./harness.js";
import { type RemoteFixture, startRemoteFixture } from "./remote-harness.js";
import { sshdUnavailable } from "./sshd.js";

const unavailable = await sshdUnavailable();

/** Installs the built bundle of extension [name] where `ensure` would, and returns the fingerprint a chat sends. */
async function installBundled(root: string, name: string): Promise<JsonObject> {
  const source = await readFile(path.join(path.dirname(inject("hostBundle")), "pipkin-extensions", `${name}.js`));
  const file = path.join(root, "extensions", name, "index.js");
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, source);
  return { name, sha256: createHash("sha256").update(source).digest("hex"), usesRuntimePackages: false };
}

describe.skipIf(unavailable !== null)(`a remote chat's Pi under the host${unavailable === null ? "" : ` (skipped: ${unavailable})`}`, () => {
  let fixture: RemoteFixture;

  beforeAll(async () => {
    fixture = await startRemoteFixture();
    await installRealPi(fixture.dirs);
  });

  afterAll(async () => {
    await fixture.stop();
  });

  it("asks the app over the channel to sign a password host with no connection in, and says it needs signing in when it can't", async () => {
    // A password host whose connection expired, renamed since it was added.
    await updateHosts(fixture.paths, (hosts) => ({
      hosts: hosts.map((host) => (host.id === fixture.host.id ? { ...host, name: "pwhost", auth: "password" as const } : host)),
      result: undefined,
    }));
    const pipkinExtensions = [await installBundled(fixture.dirs.root, "pipkin-background"), await installBundled(fixture.dirs.root, "pipkin-remote")];
    const { run } = await startHost("rpc", fixture.dirs);
    await run.next();
    run.send({
      type: "pipkin_request",
      params: { cwd: fixture.standIn, sessionPath: null, expectedVersion: PI_VERSION, packageJson: PACKAGE_JSON, pipkinExtensions },
    });
    // A `!` command runs through the same remote operations as the bash tool, with no model to ask.
    run.send({ id: "b", type: "bash", command: "hostname" });
    const seen: JsonObject[] = [];
    for (;;) {
      const message = await run.next();
      if (message === undefined) break;
      seen.push(message);
      if (message.type !== "pipkin_channel" || message.topic !== "remote" || !isObject(message.data)) continue;
      // The app has no saved password for it.
      if (message.data.reconnect === true) run.send({ type: "pipkin_channel", topic: "remote", data: { action: "reconnected", connected: false } });
      else break;
    }
    run.socket.end();
    await run.exited;
    const remote = seen.filter((message) => message.type === "pipkin_channel" && message.topic === "remote").map((message) => message.data);
    expect(remote).toEqual([
      { reconnect: true },
      {
        connected: false,
        error: { code: "remote_not_connected", message: "Not connected to pwhost. Connect to sign in.", i18n: { key: "remote_not_connected", args: { host: "pwhost" } } },
      },
    ]);
  });
});
