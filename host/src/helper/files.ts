/** The helper's files under `$HOME/.pipkin/helper/`, shared with `run.sh`, `start.sh` and the installer. */
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

export class HelperFiles {
  readonly dir: string;
  readonly helper: string;
  readonly key: string;
  readonly pid: string;
  readonly port: string;
  readonly status: string;
  readonly log: string;
  /** One `<random>.err` file per launched child, its stderr, read for the `exit` reply and then deleted. */
  readonly launches: string;

  constructor(home: string = process.env.HOME ?? os.homedir()) {
    this.dir = path.join(home, ".pipkin", "helper");
    this.helper = path.join(this.dir, "helper.mjs");
    this.key = path.join(this.dir, "key");
    this.pid = path.join(this.dir, "helper.pid");
    this.port = path.join(this.dir, "helper.port");
    this.status = path.join(this.dir, "status");
    this.log = path.join(this.dir, "helper.log");
    this.launches = path.join(this.dir, "launches");
  }

  ensureDir(): void {
    mkdirSync(this.dir, { recursive: true, mode: 0o700 });
  }

  /** The pid in `helper.pid` if it names a live helper process (another helper, or this one). */
  livePid(): number | undefined {
    let pid: number;
    try {
      pid = Number.parseInt(readFileSync(this.pid, "utf8"), 10);
    } catch {
      return undefined;
    }
    return Number.isInteger(pid) && pid > 0 && commandLine(pid).includes("helper.mjs") ? pid : undefined;
  }
}

/**
 * The process's command line, or "" if it isn't running. Termux has `/proc`; elsewhere (the tests on macOS) `ps`
 * answers the same question.
 */
function commandLine(pid: number): string {
  if (existsSync("/proc/self")) {
    try {
      return readFileSync(`/proc/${pid}/cmdline`, "utf8");
    } catch {
      return "";
    }
  }
  try {
    return execFileSync("ps", ["-o", "command=", "-p", String(pid)], { encoding: "utf8" });
  } catch {
    return "";
  }
}

/** Writes `<file>.<pid>.tmp` with mode 0600 and renames it over `file`, so readers never see a partial file. */
export function writeFileAtomic(file: string, content: string | Buffer): void {
  const temp = `${file}.${process.pid}.tmp`;
  try {
    writeFileSync(temp, content, { mode: 0o600 });
    renameSync(temp, file);
  } catch (error) {
    rmSync(temp, { force: true });
    throw error;
  }
}
