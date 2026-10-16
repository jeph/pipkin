import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installFakePi, makeDirs, PACKAGE_JSON, PI_VERSION, removeDirs, runMode, writeRuntimeManifest } from "./harness.js";

const PACKAGE_LOCK = JSON.stringify({ name: "pipkin-pi-runtime", lockfileVersion: 3 });

describe("ensure mode", () => {
  let dirs: Dirs;

  /** Runs ensure against the matching install from `beforeEach`, so it skips npm. */
  function ensure(pipkinExtensions: unknown[] = []): Promise<unknown> {
    return runMode("ensure", dirs, { packageJson: PACKAGE_JSON, packageLock: PACKAGE_LOCK, expectedVersion: PI_VERSION, pipkinExtensions });
  }

  beforeEach(async () => {
    dirs = await makeDirs();
    // An install that already matches, so ensure skips npm.
    await installFakePi(dirs);
    await writeRuntimeManifest(dirs);
    await writeFile(path.join(dirs.root, "pi/package-lock.json"), PACKAGE_LOCK);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("leaves terminal Pi's config and shell profile alone", async () => {
    const terminalPi = path.join(dirs.home, ".pi/agent");
    await mkdir(terminalPi, { recursive: true });
    await writeFile(path.join(terminalPi, "settings.json"), JSON.stringify({ defaultProvider: "anthropic" }));
    const bashrc = path.join(dirs.home, ".bashrc");
    await writeFile(bashrc, "alias ll='ls -l'\n");

    const result = await ensure();

    expect(result).toMatchObject({ ok: true, data: { piVersion: PI_VERSION } });
    expect(await readFile(bashrc, "utf8")).toBe("alias ll='ls -l'\n");
    expect(await readdir(dirs.configDir)).toEqual([]);
  });

  it("installs an extension that uses runtime packages inside the runtime, and any other outside it", async () => {
    const result = await ensure([
      { name: "pipkin-todo", source: "todo\n", usesRuntimePackages: false },
      { name: "pipkin-web-access", source: "web\n", usesRuntimePackages: true },
    ]);

    expect(result).toMatchObject({ ok: true });
    expect(await readFile(path.join(dirs.root, "extensions/pipkin-todo/index.js"), "utf8")).toBe("todo\n");
    expect(await readFile(path.join(dirs.root, "pi/extensions/pipkin-web-access/index.js"), "utf8")).toBe("web\n");
  });

  it("replaces an extension an older Pipkin installed", async () => {
    const file = path.join(dirs.root, "extensions/pipkin-todo/index.js");
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, "old\n");

    await ensure([{ name: "pipkin-todo", source: "new\n", usesRuntimePackages: false }]);

    expect(await readFile(file, "utf8")).toBe("new\n");
  });

  it("refuses an extension name that is not a plain folder name", async () => {
    const result = await ensure([{ name: "../escape", source: "x", usesRuntimePackages: false }]);

    expect(result).toMatchObject({ ok: false, code: "bad_params" });
    expect(await readdir(dirs.root)).not.toContain("escape");
  });
});
