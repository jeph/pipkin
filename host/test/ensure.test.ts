import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installFakePi, makeDirs, PACKAGE_JSON, PI_VERSION, removeDirs, runMode, writeRuntimeManifest } from "./harness.js";

const PACKAGE_LOCK = JSON.stringify({ name: "pipkin-pi-runtime", lockfileVersion: 3 });

describe("ensure mode", () => {
  let dirs: Dirs;

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

    const result = await runMode("ensure", dirs, { packageJson: PACKAGE_JSON, packageLock: PACKAGE_LOCK, expectedVersion: PI_VERSION });

    expect(result).toMatchObject({ ok: true, data: { piVersion: PI_VERSION } });
    expect(await readFile(bashrc, "utf8")).toBe("alias ll='ls -l'\n");
    expect(await readdir(dirs.configDir)).toEqual([]);
  });
});
