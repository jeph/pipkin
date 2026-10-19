/**
 * The helper's files, in the folder `helper.mjs` runs from: `$HOME/.pipkin/helper/` for the release and
 * `$HOME/.pipkin-dev/helper/` for the debug build. Shared with `run.sh`, `start.sh` and the installer.
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export class HelperFiles {
  readonly helper: string;
  readonly key: string;
  readonly pid: string;
  readonly port: string;
  /** The ports this build's helper may listen on, comma-separated, written by the installer. */
  readonly ports: string;
  readonly status: string;
  readonly log: string;
  /** One `<random>.err` file per launched child, its stderr, read for the `exit` reply and then deleted. */
  readonly launches: string;

  constructor(readonly dir: string = path.dirname(fileURLToPath(import.meta.url))) {
    this.helper = path.join(dir, "helper.mjs");
    this.key = path.join(dir, "key");
    this.pid = path.join(dir, "helper.pid");
    this.port = path.join(dir, "helper.port");
    this.ports = path.join(dir, "ports");
    this.status = path.join(dir, "status");
    this.log = path.join(dir, "helper.log");
    this.launches = path.join(dir, "launches");
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
