import { setTimeout as delay } from "node:timers/promises";
import { describe, expect, it } from "vitest";
import { spawnOwned, stopOwned } from "../src/owned.js";
import { Refusal } from "../src/wire.js";

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

describe("stopOwned", () => {
  it("stops a child that ignores stdin EOF together with its grandchildren", async () => {
    // The child prints its grandchild's pid, then sleeps regardless of stdin; the grandchild
    // sleeps independently. Both must be gone once stopOwned resolves.
    const owned = await spawnOwned("/bin/sh", ["-c", "sleep 30 & echo $!; sleep 30"], { cwd: "/", env: { PATH: "/usr/bin:/bin" } });
    let grandchildText = "";
    owned.child.stdout.on("data", (chunk: Buffer) => {
      grandchildText += chunk.toString();
    });
    while (!grandchildText.includes("\n")) await delay(10);
    const grandchild = Number(grandchildText.trim());
    expect(alive(owned.pid)).toBe(true);
    expect(alive(grandchild)).toBe(true);
    await stopOwned(owned);
    expect(owned.exited).toBe(true);
    expect(alive(owned.pid)).toBe(false);
    expect(alive(grandchild)).toBe(false);
  });

  it("returns promptly for a child that exits on stdin EOF and is idempotent", async () => {
    const owned = await spawnOwned("/bin/cat", [], { cwd: "/", env: {} });
    const started = Date.now();
    await stopOwned(owned);
    await stopOwned(owned);
    expect(owned.code).toBe(0);
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("refuses to start a missing executable", async () => {
    await expect(spawnOwned("/nonexistent/pipkin-no-such-binary", [], { cwd: "/", env: {} })).rejects.toBeInstanceOf(Refusal);
  });
});
