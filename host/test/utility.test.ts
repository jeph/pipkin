import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type Dirs, type HostRun, installRealPi, makeDirs, removeDirs, startHost } from "./harness.js";

describe("utility mode", () => {
  let dirs: Dirs;
  let run: HostRun;

  beforeEach(async () => {
    dirs = await makeDirs();
    await installRealPi(dirs);
    ({ run } = await startHost("utility", dirs));
    await run.next();
    run.send({ type: "pipkin_request", params: {} });
    const ready = await run.next();
    if (ready?.type !== "pipkin_utility_ready") throw new Error(`utility sent ${JSON.stringify(ready)} instead of its ready line`);
  });

  afterEach(async () => {
    run.socket.destroy();
    await removeDirs(dirs);
  });

  /** Every reply until one has arrived for each of [count] requests, keyed by id. */
  async function replies(count: number): Promise<Map<unknown, unknown>> {
    const byId = new Map<unknown, unknown>();
    while (byId.size < count) {
      const message = await run.next();
      if (message === undefined) throw new Error("utility closed before answering");
      byId.set(message.id, message);
    }
    return byId;
  }

  it("answers requests sent together, each under its own id, and keeps serving after a refusal", async () => {
    run.send({ type: "pipkin_utility_request", id: 1, op: "sessions", params: {} });
    run.send({ type: "pipkin_utility_request", id: 2, op: "session_messages", params: { path: "/gone.jsonl" } });
    run.send({ type: "pipkin_utility_request", id: 3, op: "backups", params: {} });
    const first = await replies(3);
    expect(first.get(1)).toEqual({ type: "pipkin_result", id: 1, ok: true, data: { sessions: [] } });
    expect(first.get(2)).toMatchObject({ type: "pipkin_result", id: 2, ok: false, code: "not_found" });
    expect(first.get(3)).toMatchObject({ type: "pipkin_result", id: 3, ok: true, data: { backups: [] } });

    run.send({ type: "pipkin_utility_request", id: 4, op: "sessions", params: {} });
    expect((await replies(1)).get(4)).toEqual({ type: "pipkin_result", id: 4, ok: true, data: { sessions: [] } });
  });

  it("refuses an operation that needs a host of its own, and malformed params", async () => {
    run.send({ type: "pipkin_utility_request", id: 1, op: "login", params: {} });
    run.send({ type: "pipkin_utility_request", id: 2, op: "sessions", params: "all" });
    const answered = await replies(2);
    expect(answered.get(1)).toMatchObject({ ok: false, code: "unknown_op" });
    expect(answered.get(2)).toMatchObject({ ok: false, code: "bad_request" });
  });

  it("exits once the app hangs up", async () => {
    run.socket.end();
    expect(await run.exited).toBe(0);
  });
});
