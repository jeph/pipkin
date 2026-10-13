import { describe, expect, it } from "vitest";
import { parseProcStat, sessionHasWork, type ProcStat } from "../src/modes/terminal.js";

/** A `/proc/<pid>/stat` line with the fields the parser reads; the rest are placeholders. */
function statLine(pid: number, comm: string, ppid: number, session: number): string {
  return `${pid} (${comm}) S ${ppid} ${session} ${session} 34816 ${session} 4194560 0 0 0 0 0 0 0 0 20 0 1 0 1`;
}

describe("parseProcStat", () => {
  it("reads pid, parent and session", () => {
    expect(parseProcStat(statLine(200, "bash", 100, 200))).toEqual({ pid: 200, ppid: 100, session: 200 });
  });

  it("reads the fields after a command name holding spaces and parentheses", () => {
    expect(parseProcStat(statLine(300, "my (odd) prog) S 1 1", 200, 200))).toEqual({
      pid: 300,
      ppid: 200,
      session: 200,
    });
  });

  it("rejects text that isn't a stat line", () => {
    expect(parseProcStat("")).toBeUndefined();
    expect(parseProcStat("300 bash S 200")).toBeUndefined();
  });
});

describe("sessionHasWork", () => {
  const script: ProcStat = { pid: 100, ppid: 1, session: 1 };
  const shell: ProcStat = { pid: 200, ppid: 100, session: 200 };

  it("is idle with only the shell in its session", () => {
    expect(sessionHasWork([script, shell], script.pid)).toBe(false);
  });

  it("counts a foreground command", () => {
    expect(sessionHasWork([script, shell, { pid: 300, ppid: 200, session: 200 }], script.pid)).toBe(true);
  });

  it("counts a background job's grandchild left behind after its parent exited", () => {
    expect(sessionHasWork([script, shell, { pid: 400, ppid: 1, session: 200 }], script.pid)).toBe(true);
  });

  it("ignores processes in other sessions, including one a command detached with setsid", () => {
    const detached = { pid: 500, ppid: 200, session: 500 };
    const termux = { pid: 600, ppid: 1, session: 600 };
    expect(sessionHasWork([script, shell, detached, termux], script.pid)).toBe(false);
  });

  it("is idle before the shell has started", () => {
    expect(sessionHasWork([script], script.pid)).toBe(false);
  });
});
