import { describe, expect, it } from "vitest";
import {
  applyTodo,
  changesState,
  commandText,
  EMPTY_STATE,
  replayBranch,
  type TodoParams,
  type TodoState,
  toolResult,
  widgetLines,
} from "../src/pi-extensions/pipkin-todo/todo.js";

/** Applies `calls` in order, failing the test on any rejected call. */
function apply(calls: TodoParams[], state: TodoState = EMPTY_STATE): TodoState {
  return calls.reduce((current, params) => {
    const applied = applyTodo(current, params);
    expect(applied.op.kind).not.toBe("error");
    return applied.state;
  }, state);
}

/** The text the model reads for `params` applied to `state`. */
function reply(state: TodoState, params: TodoParams): string {
  return toolResult(params, applyTodo(state, params)).content[0].text;
}

/** Marks each color as `<color>text</>`, so tests read which part got which color without ANSI escapes. */
const theme = { fg: (color: string, text: string) => `<${color}>${text}</>` };

const three = apply([
  { action: "create", subject: "Read the code" },
  { action: "create", subject: "Write the fix", activeForm: "Writing the fix" },
  { action: "create", subject: "Run tests" },
]);

describe("todo tool", () => {
  it("numbers tasks from 1 and keeps counting after a delete", () => {
    const state = apply([{ action: "delete", id: 3 }, { action: "create", subject: "Ship" }], three);
    expect(state.tasks.map((task) => [task.id, task.status])).toEqual([
      [1, "pending"],
      [2, "pending"],
      [3, "deleted"],
      [4, "pending"],
    ]);
  });

  it("restarts numbering after clear", () => {
    expect(reply(three, { action: "clear" })).toBe("Cleared 3 tasks");
    expect(apply([{ action: "clear" }, { action: "create", subject: "Again" }], three).tasks).toEqual([
      { id: 1, subject: "Again", status: "pending" },
    ]);
  });

  it.each([
    [{ action: "create" }, "Error: subject required for create"],
    [{ action: "create", subject: "   " }, "Error: subject required for create"],
    [{ action: "update", id: 9, status: "completed" }, "Error: #9 not found"],
    [{ action: "update", id: 1 }, "Error: update requires at least one mutable field"],
    [{ action: "get" }, "Error: id required for get"],
    [{ action: "create", subject: "x", blockedBy: [7] }, "Error: blockedBy: #7 not found"],
    [{ action: "update", id: 1, addBlockedBy: [1] }, "Error: cannot block #1 on itself"],
  ] as [TodoParams, string][])("rejects %j", (params, message) => {
    expect(reply(three, params)).toContain(message);
    expect(applyTodo(three, params).state).toBe(three);
  });

  it("allows only forward moves out of completed, and nothing out of deleted", () => {
    const done = apply([{ action: "update", id: 1, status: "completed" }], three);
    expect(reply(done, { action: "update", id: 1, status: "in_progress" })).toBe(
      "Error: illegal transition completed → in_progress",
    );
    const deleted = apply([{ action: "delete", id: 2 }], three);
    expect(reply(deleted, { action: "update", id: 2, status: "pending" })).toBe("Error: illegal transition deleted → pending");
    expect(reply(deleted, { action: "delete", id: 2 })).toBe("Error: #2 is already deleted");
    expect(reply(deleted, { action: "update", id: 3, addBlockedBy: [2] })).toBe("Error: addBlockedBy: #2 is deleted");
  });

  it("refuses a dependency that closes a cycle, through other tasks too", () => {
    const chained = apply([
      { action: "update", id: 2, addBlockedBy: [1] },
      { action: "update", id: 3, addBlockedBy: [2] },
    ], three);
    expect(reply(chained, { action: "update", id: 1, addBlockedBy: [3] })).toBe(
      "Error: addBlockedBy would create a cycle in the blockedBy graph",
    );
    expect(reply(chained, { action: "get", id: 2 })).toBe("#2 [pending] Write the fix\n  activeForm: Writing the fix\n  blockedBy: #1\n  blocks: #3");
  });

  it("reports a repeated update as no change, which saves nothing", () => {
    const started = apply([{ action: "update", id: 1, status: "in_progress" }], three);
    const again = applyTodo(started, { action: "update", id: 1, status: "in_progress" });
    expect(changesState(again.op)).toBe(false);
    expect(toolResult({ action: "update", id: 1, status: "in_progress" }, again).content[0].text).toBe(
      "No change: #1 already matches the requested values (status: in_progress)",
    );
  });

  it("merges metadata, a null value removing its key", () => {
    const state = apply([
      { action: "update", id: 1, metadata: { a: 1, b: 2 } },
      { action: "update", id: 1, metadata: { a: null, c: 3 } },
    ], three);
    expect(state.tasks[0]?.metadata).toEqual({ b: 2, c: 3 });
    expect(apply([{ action: "update", id: 1, metadata: { b: null, c: null } }], state).tasks[0]).not.toHaveProperty("metadata");
  });

  it("lists tasks by status, deleted ones only when asked", () => {
    const state = apply([{ action: "update", id: 2, status: "in_progress" }, { action: "delete", id: 3 }], three);
    expect(reply(state, { action: "list" })).toBe("[pending] #1 Read the code\n[in_progress] #2 Write the fix (Writing the fix)");
    expect(reply(state, { action: "list", status: "deleted" })).toBe("No tasks");
    expect(reply(state, { action: "list", status: "deleted", includeDeleted: true })).toBe("[deleted] #3 Run tests");
  });

  it("strips terminal controls from what it shows, keeping each field on one line", () => {
    const state = apply([{ action: "create", subject: "a\u001b[31mred\u001b[0m\nb\u202eevil" }]);
    expect(reply(state, { action: "list" })).toBe("[pending] #1 ared bevil");
  });
});

/** A branch entry rpiv-todo or this extension saves: the list after a call. */
const snapshot = (state: TodoState) => ({ tasks: state.tasks, nextId: state.nextId, action: "create", params: {} });
const custom = (state: TodoState) => ({ type: "custom", customType: "pipkin-todo-state", data: snapshot(state) });
const result = (state: TodoState, toolName = "todo") => ({
  type: "message",
  message: { role: "toolResult", toolName, details: snapshot(state) },
});

describe("replay", () => {
  const one = apply([{ action: "create", subject: "One" }]);

  it("takes the newest saved list on the branch", () => {
    expect(replayBranch([custom(one), { type: "message", message: { role: "user" } }, custom(three)], "pipkin-todo-state")).toEqual(three);
  });

  it("falls back to the last todo tool result, as rpiv-todo left a branch", () => {
    expect(replayBranch([result(one), result(three, "bash"), result(EMPTY_STATE, "todo")], "pipkin-todo-state")).toEqual(EMPTY_STATE);
    expect(replayBranch([result(three), custom(one)], "pipkin-todo-state")).toEqual(one);
  });

  it("starts empty on a branch without a list, skipping malformed entries", () => {
    expect(replayBranch([{ type: "custom", customType: "pipkin-todo-state", data: { tasks: "no" } }], "pipkin-todo-state")).toEqual(EMPTY_STATE);
  });
});

describe("widget", () => {
  it("is hidden while no task is left to show", () => {
    expect(widgetLines(EMPTY_STATE, theme)).toBeNull();
    expect(widgetLines(apply([{ action: "delete", id: 1 }], apply([{ action: "create", subject: "x" }])), theme)).toBeNull();
  });

  it("shows each task's status, strikes done ones through, and dims the heading once all are done", () => {
    const state = apply([
      { action: "update", id: 1, status: "completed" },
      { action: "update", id: 2, status: "in_progress" },
    ], three);
    expect(widgetLines(state, theme)).toEqual([
      "<accent>● Todos (1/3)</>",
      "<dim>├─</> <success>✓</> \u001b[9m<muted>Read the code</>\u001b[29m",
      "<dim>├─</> <warning>◐</> <accent>Write the fix</> <muted>(Writing the fix)</>",
      "<dim>└─</> <dim>○</> <text>Run tests</>",
    ]);
    const allDone = apply([2, 3].map((id) => ({ action: "update", id, status: "completed" }) as TodoParams), state);
    expect(widgetLines(allDone, theme)?.[0]).toBe("<dim>○ Todos (3/3)</>");
  });

  it("shows ids only when a task waits on another", () => {
    const blocked = apply([{ action: "update", id: 3, addBlockedBy: [1] }], three);
    expect(widgetLines(blocked, theme)?.[3]).toBe("<dim>└─</> <dim>○</> <dim>#3</> <text>Run tests</> <muted>⛓ #1</>");
    expect(widgetLines(three, theme)?.[1]).toBe("<dim>├─</> <dim>○</> <text>Read the code</>");
  });
});

describe("/todos", () => {
  it("groups the tasks under their counts", () => {
    const state = apply([{ action: "update", id: 1, status: "completed" }, { action: "update", id: 2, status: "in_progress" }], three);
    expect(commandText(state)).toBe(
      [
        "1/3 completed · 1 in progress · 1 pending",
        "── Pending ──",
        "  ○ #3 Run tests",
        "── In Progress ──",
        "  ◐ #2 Write the fix (Writing the fix)",
        "── Completed ──",
        "  ✓ #1 Read the code",
      ].join("\n"),
    );
    expect(commandText(EMPTY_STATE)).toBeNull();
  });
});
