import { access, readFile, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode, startHost } from "./harness.js";

describe("update_extensions mode", () => {
  let dirs: Dirs;
  let calls: string;

  /**
   * Points Pi's settings at `packages` and at a stand-in npm that records each call's arguments, working folder and
   * pid, prints `output`, and exits with `exitCode`, or never exits when `hang`.
   */
  async function configure(packages: string[], output = "", exitCode = 0, hang = false): Promise<void> {
    const npm = path.join(dirs.base, "fake-npm.mjs");
    const record = "JSON.stringify({ args: process.argv.slice(2), cwd: process.cwd(), pid: process.pid })";
    await writeFile(
      npm,
      [
        'import { appendFileSync } from "node:fs";',
        `appendFileSync(${JSON.stringify(calls)}, ${record} + "\\n");`,
        `if (${JSON.stringify(output)}) console.error(${JSON.stringify(output)});`,
        hang ? "setInterval(() => undefined, 1000);" : `process.exit(${exitCode});`,
      ].join("\n"),
    );
    await writeFile(path.join(dirs.configDir, "settings.json"), JSON.stringify({ packages, npmCommand: [process.execPath, npm] }));
  }

  async function npmCalls(): Promise<{ args: string[]; cwd: string; pid: number }[]> {
    const text = await readFile(calls, "utf8").catch(() => "");
    return text.split("\n").filter(Boolean).map((line) => JSON.parse(line) as { args: string[]; cwd: string; pid: number });
  }

  beforeEach(async () => {
    dirs = await makeDirs();
    calls = path.join(dirs.base, "npm-calls.jsonl");
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("installs the latest version of each unpinned package into Pipkin's config folder", async () => {
    await configure(["npm:pi-example", "npm:pi-pinned@1.2.3"]);

    const result = await runMode("update_extensions", dirs, {});

    expect(result).toMatchObject({ ok: true });
    const installs = (await npmCalls()).filter(({ args }) => args[0] === "install");
    expect(installs).toHaveLength(1);
    expect(installs[0]?.args).toContain("pi-example@latest");
    expect(installs[0]?.args.join(" ")).not.toContain("pi-pinned");
    expect(installs[0]?.args).toContain(path.join(dirs.configDir, "npm"));
  });

  it("succeeds without running npm when no packages are installed", async () => {
    await configure([]);

    expect(await runMode("update_extensions", dirs, {})).toMatchObject({ ok: true });
    expect(await npmCalls()).toEqual([]);
  });

  it("forwards Pi's output as progress", async () => {
    await configure(["npm:pi-example"], "added 1 package");
    const progress: string[] = [];

    await runMode("update_extensions", dirs, {}, {}, progress);

    expect(progress).toContain("Updating npm:pi-example...");
    expect(progress).toContain("added 1 package");
  });

  it("reports an unreachable registry as being offline", async () => {
    await configure(["npm:pi-example"], "npm error code EAI_AGAIN", 1);

    const result = await runMode("update_extensions", dirs, {});

    expect(result).toMatchObject({ ok: false, code: "npm_offline" });
  });

  it("reports Pi's own error when npm fails for another reason", async () => {
    await configure(["npm:pi-example"], "npm error code E404", 1);

    const result = await runMode("update_extensions", dirs, {});

    expect(result).toMatchObject({ ok: false, code: "update_failed" });
    expect(String(result.error)).toMatch(/failed with code 1$/);
    expect(String(result.error)).not.toMatch(/^Error:/);
  });

  it("refuses to run while another host holds the config lock", async () => {
    await configure(["npm:pi-example"]);
    await symlink(String(process.pid), path.join(dirs.root, "config.lock"));

    expect(await runMode("update_extensions", dirs, {})).toMatchObject({ ok: false, code: "busy" });
    expect(await npmCalls()).toEqual([]);
  });

  it("stops npm and releases the lock once the app hangs up mid-update", async () => {
    await configure(["npm:pi-example"], "npm started", 0, true);
    const { run } = await startHost("update_extensions", dirs);
    await run.next();
    run.send({ type: "pipkin_request", params: {} });
    for (;;) {
      const message = await run.next();
      if (message === undefined) throw new Error("update_extensions closed before npm started");
      if (message.line === "npm started") break;
    }

    run.socket.destroy();
    await run.exited;

    const [npm] = await npmCalls();
    expect(() => process.kill(npm?.pid ?? 0, 0)).toThrow("ESRCH");
    await expect(access(path.join(dirs.root, "config.lock"))).rejects.toThrow("ENOENT");
  });

  it("refuses to run before the agent is installed", async () => {
    await removeDirs(dirs);
    dirs = await makeDirs();

    expect(await runMode("update_extensions", dirs, {})).toMatchObject({ ok: false, code: "pi_missing" });
  });
});
