import { describe, expect, it } from "vitest";
import {
  loginShellComm,
  parseProcStat,
  sessionHasWork,
  sessionLeader,
  type ProcStat,
} from "../src/modes/terminal.js";

/** A `/proc/<pid>/stat` line with the fields the parser reads; the rest are placeholders. */
function statLine(pid: number, comm: string, ppid: number, session: number): string {
  return `${pid} (${comm}) S ${ppid} ${session} ${session} 34816 ${session} 4194560 0 0 0 0 0 0 0 0 20 0 1 0 1`;
}

describe("parseProcStat", () => {
  it("reads pid, command name, parent and session", () => {
    expect(parseProcStat(statLine(200, "bash", 100, 200))).toEqual({ pid: 200, comm: "bash", ppid: 100, session: 200 });
  });

  it("reads a command name holding spaces and parentheses, and the fields after it", () => {
    expect(parseProcStat(statLine(300, "my (odd) prog) S 1 1", 200, 200))).toEqual({
      pid: 300,
      comm: "my (odd) prog) S 1 1",
      ppid: 200,
      session: 200,
    });
  });

  it("rejects text that isn't a stat line", () => {
    expect(parseProcStat("")).toBeUndefined();
    expect(parseProcStat("300 bash S 200")).toBeUndefined();
  });
});

describe("loginShellComm", () => {
  const prefix = "/data/data/com.termux/files/usr/bin";

  it("names the program SHELL points to", () => {
    expect(loginShellComm(`HOME=/home\0SHELL=${prefix}/nu\0TERM=xterm-256color\0`)).toBe("nu");
  });

  it("cuts the name to the kernel's 15 characters, as the shell's comm is", () => {
    expect(loginShellComm(`SHELL=${prefix}/a-very-long-shell-name\0`)).toBe("a-very-long-she");
  });

  it("is undefined without SHELL, as before Termux's login exports it", () => {
    expect(loginShellComm("HOME=/home\0SHELLS=/etc/shells\0")).toBeUndefined();
    expect(loginShellComm("")).toBeUndefined();
  });
});

describe("sessionLeader", () => {
  it("is the process script started in a session of its own", () => {
    const script: ProcStat = { pid: 100, comm: "script", ppid: 1, session: 1 };
    const shell: ProcStat = { pid: 200, comm: "bash", ppid: 100, session: 200 };
    expect(sessionLeader([script, shell], script.pid)).toBe(shell);
  });

  it("is undefined before the shell has started", () => {
    expect(sessionLeader([{ pid: 100, comm: "script", ppid: 1, session: 1 }], 100)).toBeUndefined();
  });
});

describe("sessionHasWork", () => {
  const shell: ProcStat = { pid: 200, comm: "bash", ppid: 100, session: 200 };

  it("is idle with only the shell in its session", () => {
    expect(sessionHasWork([shell], shell, "bash")).toBe(false);
  });

  it("counts a foreground command", () => {
    expect(sessionHasWork([shell, { pid: 300, comm: "vim", ppid: 200, session: 200 }], shell, "bash")).toBe(true);
  });

  it("counts a background job's grandchild left behind after its parent exited", () => {
    expect(sessionHasWork([shell, { pid: 400, comm: "node", ppid: 1, session: 200 }], shell, "bash")).toBe(true);
  });

  it("ignores processes in other sessions, including one a command detached with setsid", () => {
    const detached = { pid: 500, comm: "sleep", ppid: 200, session: 500 };
    const termux = { pid: 600, comm: "bash", ppid: 1, session: 600 };
    expect(sessionHasWork([shell, detached, termux], shell, "bash")).toBe(false);
  });

  it("counts a program exec'd in place of the shell", () => {
    const vim = { ...shell, comm: "vim" };
    expect(sessionHasWork([vim], vim, "bash")).toBe(true);
  });

  it("counts a program exec'd in place of the shell when SHELL couldn't be read", () => {
    const ssh = { ...shell, comm: "ssh" };
    expect(sessionHasWork([ssh], ssh, undefined)).toBe(true);
  });

  it("is idle with a login shell only SHELL names", () => {
    const nu = { ...shell, comm: "nu" };
    expect(sessionHasWork([nu], nu, "nu")).toBe(false);
  });

  it("is idle while sh runs Termux's login as the session starts, before SHELL is set", () => {
    expect(sessionHasWork([{ ...shell, comm: "sh" }], { ...shell, comm: "sh" }, undefined)).toBe(false);
    expect(sessionHasWork([{ ...shell, comm: "login" }], { ...shell, comm: "login" }, undefined)).toBe(false);
  });

  it("is idle with another shell exec'd in place of the login shell, as an rc file may", () => {
    const zsh = { ...shell, comm: "zsh" };
    expect(sessionHasWork([zsh], zsh, "bash")).toBe(false);
  });
});
