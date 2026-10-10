import { execFile } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import type { TestProject } from "vitest/node";

declare module "vitest" {
  export interface ProvidedContext {
    hostBundle: string;
  }
}

/** Tests run the production bundle (`pnpm run build`) as a child process, as Termux would. */
export default async function setup(project: TestProject): Promise<() => Promise<void>> {
  const dir = await mkdtemp(path.join(os.tmpdir(), "pipkin-host-bundle-"));
  await promisify(execFile)("pnpm", ["run", "--silent", "build"], {
    cwd: path.join(import.meta.dirname, ".."),
    env: { ...process.env, OUT: dir },
  });
  project.provide("hostBundle", path.join(dir, "pipkin-host.mjs"));
  return () => rm(dir, { recursive: true, force: true });
}
