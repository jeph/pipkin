import { chmod, mkdir, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { JsonObject } from "../src/framing.js";
import { type Dirs, type HostRun, makeDirs, removeDirs, startHost } from "./harness.js";

describe("browse mode", () => {
  let dirs: Dirs;
  let shared: string;
  let run: HostRun;
  let nextId = 0;

  beforeEach(async () => {
    dirs = await makeDirs();
    shared = path.join(dirs.base, "shared");
    await mkdir(path.join(shared, "Documents"), { recursive: true });
    await mkdir(path.join(dirs.home, "storage"), { recursive: true });
    await symlink(shared, path.join(dirs.home, "storage", "shared"));
    for (const name of ["project10", "Project2", ".config", "alpha"]) await mkdir(path.join(dirs.home, name));
    await writeFile(path.join(dirs.home, "notes.txt"), "not a folder");
    ({ run } = await startHost("browse", dirs));
    await run.next();
    run.send({ type: "pipkin_request", params: {} });
  });

  afterEach(async () => {
    run.socket.destroy();
    await removeDirs(dirs);
  });

  async function list(target: string): Promise<JsonObject | undefined> {
    const id = ++nextId;
    run.send({ type: "pipkin_list", id, path: target });
    for (;;) {
      const message = await run.next();
      if (message === undefined || (message.type === "pipkin_listing" && message.id === id)) return message;
    }
  }

  it("starts with Termux home and the shared-storage link's real folder", async () => {
    expect(await run.next()).toEqual({
      type: "pipkin_places",
      places: [
        { kind: "home", path: dirs.home },
        { kind: "shared", path: shared },
      ],
    });
  });

  it("lists only folders, following links, with dot folders last", async () => {
    expect(await list("~")).toEqual({
      type: "pipkin_listing",
      id: nextId,
      path: dirs.home,
      parent: path.dirname(dirs.home),
      dirs: ["alpha", "Project2", "project10", "storage", ".config"],
    });
    expect(await list("~/storage")).toMatchObject({ dirs: ["shared"] });
    expect(await list("~/storage/shared")).toMatchObject({ path: shared, dirs: ["Documents"] });
  });

  it("names a folder reached through a link by its real path", async () => {
    await symlink(path.join(dirs.home, "alpha"), path.join(dirs.base, "to-alpha"));
    expect(await list(path.join(dirs.base, "to-alpha"))).toMatchObject({ path: path.join(dirs.home, "alpha") });
  });

  it("keeps Termux home's own spelling when home lies under a link", async () => {
    const linkedHome = path.join(dirs.base, "linked-home");
    await symlink(dirs.home, linkedHome);
    const { run: linked } = await startHost("browse", { ...dirs, home: linkedHome });
    try {
      await linked.next();
      linked.send({ type: "pipkin_request", params: {} });
      await linked.next();
      linked.send({ type: "pipkin_list", id: 1, path: path.join(dirs.home, "alpha") });
      expect(await linked.next()).toMatchObject({ type: "pipkin_listing", path: path.join(linkedHome, "alpha") });
    } finally {
      linked.socket.destroy();
    }
  });

  it("normalises .. and stops the parent at /", async () => {
    expect(await list("~/alpha/../Project2")).toMatchObject({ path: path.join(dirs.home, "Project2"), dirs: [] });
    expect(await list("/")).toMatchObject({ path: "/", parent: null });
  });

  it("offers no parent that Termux can pass through but not list", async () => {
    const locked = path.join(dirs.home, "locked");
    await mkdir(path.join(locked, "inner"), { recursive: true });
    await chmod(locked, 0o311);
    try {
      expect(await list("~/locked/inner")).toMatchObject({ path: path.join(locked, "inner"), parent: null, dirs: [] });
    } finally {
      await chmod(locked, 0o755);
    }
  });

  it("reports unreadable paths in the listing instead of failing the session", async () => {
    expect(await list(path.join(dirs.home, "missing"))).toMatchObject({ dirs: [], error: "This folder doesn't exist." });
    expect(await list(path.join(dirs.home, "notes.txt"))).toMatchObject({ dirs: [], error: "This isn't a folder." });
    expect(await list("relative/path")).toMatchObject({ parent: null, dirs: [], error: "Paths start with / or ~." });
    expect(await list("~")).toMatchObject({ path: dirs.home });
  });

  it("exits once the app hangs up", async () => {
    run.socket.end();
    expect(await run.exited).not.toBeUndefined();
  });
});
