import { existsSync, readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  detachedReason,
  readTail,
  resultText,
  signalGroup,
  startProcess,
  type Task,
} from "../src/pi-extensions/pipkin-background/tasks.js";

const SHELL = { shell: "/bin/sh", args: ["-c"] };

describe("detachedReason", () => {
  it.each([
    ["python3 -m http.server 8000 &", "`&`"],
    ["npm run dev > out.log 2>&1 &", "`&`"],
    ["(sleep 5; echo hi) & echo started", "`&`"],
    ["nohup ./server > log 2>&1", "`nohup`"],
    ["PORT=3000 nohup node server.js", "`nohup`"],
    ["cd app\nnohup ./server", "`nohup`"],
    ["cd app && setsid node server.js", "`setsid`"],
    ["./run & disown", "`disown`"],
    ["tmux new -d -s dev 'npm start'", "`tmux`"],
    ["tmux send-keys -t dev 'make' Enter", "`tmux`"],
    ["screen -dmS build make", "`screen`"],
  ])("blocks %s", (command, reason) => {
    expect(detachedReason(command)).toBe(reason);
  });

  it.each([
    "echo a && echo b",
    "ls 2>&1 | grep x",
    "make &> build.log",
    "cmd |& tee log",
    "exec 3<&0",
    "curl 'http://x/?a=1&b=2'",
    `echo "nohup & tmux new"`,
    "a & b & wait",
    "sleep 1 & pid=$!; kill $pid",
    "tmux ls",
    "screen -ls",
    "grep -r nohup docs",
    "man setsid",
  ])("allows %s", (command) => {
    expect(detachedReason(command)).toBeNull();
  });
});

describe("results", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(path.join(tmpdir(), "pipkin-background-"));
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  const task = (overrides: Partial<Task>): Task => ({
    id: "bg1",
    label: "build",
    command: "make",
    cwd: dir,
    sessionId: "s",
    logPath: path.join(dir, "bg1.log"),
    startedAt: 1_000,
    endedAt: 126_000,
    status: "exited",
    exitCode: 0,
    signal: null,
    ...overrides,
  });

  it("gives the last lines without terminal escapes or progress redraws", async () => {
    const log = path.join(dir, "bg1.log");
    await writeFile(log, "\u001b[32mok\u001b[0m\n 10%\r 50%\r100%\ndone\n\n");
    expect(readTail(log)).toBe("ok\n100%\ndone");
  });

  it("drops the cut first line of a long log and keeps only the last lines", async () => {
    const log = path.join(dir, "bg1.log");
    const lines = Array.from({ length: 1000 }, (_, index) => `line ${index}`);
    await writeFile(log, `${lines.join("\n")}\n`);
    const tail = readTail(log).split("\n");
    expect(tail).toHaveLength(40);
    expect(tail.at(-1)).toBe("line 999");
    expect(tail[0]).toBe("line 960");
  });

  it("reads nothing from a missing log", () => {
    expect(readTail(path.join(dir, "missing.log"))).toBe("");
  });

  it("says how each task ended, with its output or that it printed nothing", async () => {
    await writeFile(path.join(dir, "bg1.log"), "compiled\n");
    const text = resultText([
      task({}),
      task({ id: "bg2", label: "server", logPath: path.join(dir, "bg2.log"), exitCode: null, signal: "SIGTERM" }),
      task({ id: "bg3", label: "bad", status: "failed", error: "spawn /bin/sh ENOENT" }),
    ]);
    expect(text).toBe(
      [
        `Background task bg1 (build) exit 0 after 2m 5s.\nLast output:\n\`\`\`\ncompiled\n\`\`\`\nFull output: ${path.join(dir, "bg1.log")}`,
        `Background task bg2 (server) ended by SIGTERM after 2m 5s.\nIt printed nothing.\nFull output: ${path.join(dir, "bg2.log")}`,
        "Background task bg3 (bad) failed to start: spawn /bin/sh ENOENT.",
      ].join("\n\n"),
    );
  });
});

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/**
 * Polls [check] for up to two seconds. These are real processes outside this one, which signal nothing on starting or
 * dying, so there's no event to await and no clock to fake.
 */
async function eventually(check: () => boolean): Promise<boolean> {
  for (let tries = 0; tries < 100; tries++) {
    if (check()) return true;
    await sleep(20);
  }
  return check();
}

describe("task processes", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(path.join(tmpdir(), "pipkin-background-"));
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("logs the command's output and reports its exit", async () => {
    const log = path.join(dir, "out.log");
    const started = startProcess(SHELL, "echo out; echo err >&2; exit 3", dir, log);
    expect(await started.ended).toEqual({ code: 3, signal: null });
    expect(readTail(log)).toBe("out\nerr");
  });

  it("stops the command's whole process group, children included", async () => {
    const pidFile = path.join(dir, "child.pid");
    const command = `sleep 300 & echo $! > ${pidFile}.tmp; mv ${pidFile}.tmp ${pidFile}; wait`;
    const started = startProcess(SHELL, command, dir, path.join(dir, "out.log"));
    expect(await eventually(() => existsSync(pidFile))).toBe(true);
    const child = Number(readFileSync(pidFile, "utf8").trim());
    expect(alive(child)).toBe(true);

    expect(signalGroup(started.pid, "SIGTERM")).toBe(true);
    expect(await started.ended).toMatchObject({ signal: "SIGTERM" });
    expect(await eventually(() => !alive(child))).toBe(true);
  });

  it("refuses to signal without a process, which would reach this process's own group", () => {
    expect(signalGroup(undefined, "SIGTERM")).toBe(false);
    expect(signalGroup(0, "SIGTERM")).toBe(false);
    expect(signalGroup(-1, "SIGTERM")).toBe(false);
  });

  it("reports a start that fails", async () => {
    const started = startProcess({ shell: path.join(dir, "no-shell"), args: ["-c"] }, "true", dir, path.join(dir, "out.log"));
    await expect(started.ended).rejects.toThrow(/ENOENT/);
  });
});
