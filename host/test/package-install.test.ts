import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, installRealPi, makeDirs, removeDirs, runMode } from "./harness.js";

describe("package_install", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("adds the package to Pi's global settings", async () => {
    const packageDir = path.join(dirs.base, "plugin");
    await mkdir(packageDir);
    await writeFile(path.join(packageDir, "package.json"), JSON.stringify({ name: "plugin", version: "1.0.0" }));
    const progress: string[] = [];
    expect(await runMode("package_install", dirs, { source: packageDir }, {}, progress)).toMatchObject({ ok: true });
    const settings = JSON.parse(await readFile(path.join(dirs.configDir, "settings.json"), "utf8")) as { packages: string[] };
    // Pi stores a local path relative to its config folder.
    expect(settings.packages.map((source) => path.resolve(dirs.configDir, source))).toEqual([packageDir]);
    expect(progress.join("\n")).toContain(packageDir);
  });

  it("fails with Pi's reason when the package cannot be installed", async () => {
    const missing = path.join(dirs.base, "missing");
    const result = await runMode("package_install", dirs, { source: missing });
    expect(result).toMatchObject({ ok: false, code: "install_failed", error: expect.stringContaining(missing) });
  });
});

describe("auth_status npmPackages", () => {
  let dirs: Dirs;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
  });

  afterEach(async () => {
    await removeDirs(dirs);
  });

  it("names the npm packages in the global settings, without versions", async () => {
    const packages = ["npm:@gotgenes/pi-anthropic-auth@3.1.0", { source: "npm:pi-web-access" }, "./local", "git:github.com/a/b"];
    await writeFile(path.join(dirs.configDir, "settings.json"), JSON.stringify({ packages }));
    const status = await runMode("auth_status", dirs, {});
    expect(status).toMatchObject({ ok: true, data: { npmPackages: ["@gotgenes/pi-anthropic-auth", "pi-web-access"] } });
  });
});
