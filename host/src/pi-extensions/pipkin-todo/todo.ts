/**
 * The `todo` tool's state, results and display, without Pi's runtime.
 *
 * Everything the model sees is copied from @juicesharp/rpiv-todo 2.13.8 (https://github.com/juicesharp/rpiv-mono,
 * MIT licence, Copyright (c) 2026 juicesharp), so a model that knows that tool uses this one the same way: the tool's
 * name, description, prompt snippet and guidelines, its parameter schema, the status rules, and the result and error
 * texts. The `/todos` command's texts come from it too. The code is Pipkin's own; the widget follows rpiv-todo's
 * overlay but lists every task, since Pipkin's widget panel scrolls instead of truncating.
 */
import { StringEnum } from "@earendil-works/pi-ai";
import { type Static, Type } from "typebox";

export type TaskStatus = "pending" | "in_progress" | "completed" | "deleted";

export type TaskAction = "create" | "update" | "list" | "get" | "delete" | "clear";

export interface Task {
  id: number;
  subject: string;
  description?: string;
  activeForm?: string;
  status: TaskStatus;
  blockedBy?: number[];
  owner?: string;
  metadata?: Record<string, unknown>;
}

export interface TodoState {
  tasks: Task[];
  nextId: number;
}

export const EMPTY_STATE: TodoState = { tasks: [], nextId: 1 };

/** A call's `details`: the whole list after it, which replay reads back from the session. */
export interface TodoDetails {
  action: TaskAction;
  params: Record<string, unknown>;
  tasks: Task[];
  nextId: number;
  error?: string;
}

export const TOOL_NAME = "todo";

export const TOOL_LABEL = "Todo";

export const TOOL_DESCRIPTION =
  "Manage a task list for tracking multi-step progress. Actions: create (new task), update (change status/fields/dependencies), list (all tasks, optionally filtered by status), get (single task details), delete (tombstone), clear (reset all). Status: pending → in_progress → completed, plus deleted tombstone. Use this to plan and track multi-step work like research, design, and implementation.";

export const PROMPT_SNIPPET = "Manage a task list to track multi-step progress";

export const PROMPT_GUIDELINES: string[] = [
  "Use `todo` for complex work with 3+ steps, when the user gives you a list of tasks, or immediately after receiving new instructions to capture requirements. Skip it for single trivial tasks and purely conversational requests.",
  "When starting a task from the todo list, mark it in_progress BEFORE beginning work. Mark it completed IMMEDIATELY when done — never batch completions. Exactly one task in_progress at a time.",
  "Never mark a task completed if tests are failing, the implementation is partial, or you hit unresolved errors — keep it in_progress and create a new task for the blocker instead.",
  "Task status is a 4-state machine: pending → in_progress → completed, plus deleted as a tombstone. Pass activeForm (present-continuous label, e.g. 'researching existing tool') when marking in_progress.",
  'To change a task\'s status, call update with the task id and the target status, e.g. {"action":"update","id":3,"status":"completed"} or {"action":"update","id":3,"status":"in_progress","activeForm":"writing tests"}. status is the field that changes the task; an update without a mutable field (status or another) is rejected.',
  "Use blockedBy to express dependencies (A is blocked by B). On create, pass blockedBy as the initial set. On update, use addBlockedBy / removeBlockedBy (additive merge — do not resend the full array). Cycles are rejected.",
  "list hides tombstoned (deleted) tasks by default; pass includeDeleted:true to see them. Pass status to filter by a single status.",
  "Subject must be short and imperative (e.g. 'Research existing tool'); description is for long-form detail. activeForm is a present-continuous label shown while in_progress.",
];

export const TodoParams = Type.Object({
  action: StringEnum(["create", "update", "list", "get", "delete", "clear"] as const),
  subject: Type.Optional(Type.String({ description: "Task subject line (required for create)" })),
  description: Type.Optional(Type.String({ description: "Long-form task description" })),
  activeForm: Type.Optional(
    Type.String({
      description: "Present-continuous spinner label shown while status is in_progress (e.g. 'writing tests')",
    }),
  ),
  status: Type.Optional(
    StringEnum(["pending", "in_progress", "completed", "deleted"] as const, {
      description:
        "Set this task's status (update): one of pending, in_progress, completed, deleted. When action is list, filters returned tasks by this status.",
    }),
  ),
  blockedBy: Type.Optional(Type.Array(Type.Number(), { description: "Initial blockedBy ids (create only)" })),
  addBlockedBy: Type.Optional(
    Type.Array(Type.Number(), { description: "Task ids to add to blockedBy (update only, additive merge)" }),
  ),
  removeBlockedBy: Type.Optional(
    Type.Array(Type.Number(), { description: "Task ids to remove from blockedBy (update only, additive merge)" }),
  ),
  owner: Type.Optional(Type.String({ description: "Agent/owner assigned to this task" })),
  metadata: Type.Optional(
    Type.Record(Type.String(), Type.Unknown(), {
      description: "Arbitrary metadata; pass null value for a key to delete that key on update",
    }),
  ),
  id: Type.Optional(Type.Number({ description: "Task id (required for update, get, delete)" })),
  includeDeleted: Type.Optional(
    Type.Boolean({
      description: "If true, list action returns deleted (tombstoned) tasks as well. Default: false.",
    }),
  ),
});

export type TodoParams = Static<typeof TodoParams>;

/** What a call did; [formatContent] turns it into the text the model reads. */
export type Op =
  | { kind: "create"; task: Task }
  | { kind: "update"; id: number; from: TaskStatus; to: TaskStatus; changed: boolean }
  | { kind: "delete"; task: Task }
  | { kind: "list"; status: TaskStatus | undefined; includeDeleted: boolean }
  | { kind: "get"; task: Task }
  | { kind: "clear"; count: number }
  | { kind: "error"; message: string };

export interface Applied {
  state: TodoState;
  op: Op;
}

/** The statuses each status may move to; a task may always be set to the status it has. */
const TRANSITIONS: Record<TaskStatus, readonly TaskStatus[]> = {
  pending: ["in_progress", "completed", "deleted"],
  in_progress: ["pending", "completed", "deleted"],
  completed: ["deleted"],
  deleted: [],
};

const MUTABLE_FIELDS =
  "subject, description, activeForm, status, owner, metadata, addBlockedBy, or removeBlockedBy";

/** Whether [op] changed the list, so the list after it must be saved for replay. */
export function changesState(op: Op): boolean {
  switch (op.kind) {
    case "create":
    case "delete":
    case "clear":
      return true;
    case "update":
      return op.changed;
    case "list":
    case "get":
    case "error":
      return false;
  }
}

/** Applies one `todo` call to [state]. A rejected call returns [state] unchanged with an `error` op. */
export function applyTodo(state: TodoState, params: TodoParams): Applied {
  const fail = (message: string): Applied => ({ state, op: { kind: "error", message } });
  switch (params.action) {
    case "create": {
      if (!params.subject?.trim()) return fail("subject required for create");
      for (const dep of params.blockedBy ?? []) {
        const problem = dependencyProblem(state, dep);
        if (problem) return fail(`blockedBy: ${problem}`);
      }
      const task: Task = { id: state.nextId, subject: params.subject, status: "pending" };
      if (params.description) task.description = params.description;
      if (params.activeForm) task.activeForm = params.activeForm;
      if (params.blockedBy?.length) task.blockedBy = [...params.blockedBy];
      if (params.owner) task.owner = params.owner;
      if (params.metadata) task.metadata = { ...params.metadata };
      return { state: { tasks: [...state.tasks, task], nextId: state.nextId + 1 }, op: { kind: "create", task } };
    }
    case "update":
      return update(state, params, fail);
    case "list":
      return { state, op: { kind: "list", status: params.status, includeDeleted: params.includeDeleted === true } };
    case "get": {
      if (params.id === undefined) return fail("id required for get");
      const task = state.tasks.find((candidate) => candidate.id === params.id);
      return task ? { state, op: { kind: "get", task } } : fail(`#${params.id} not found`);
    }
    case "delete": {
      if (params.id === undefined) return fail("id required for delete");
      const task = state.tasks.find((candidate) => candidate.id === params.id);
      if (!task) return fail(`#${params.id} not found`);
      if (task.status === "deleted") return fail(`#${task.id} is already deleted`);
      const deleted: Task = { ...task, status: "deleted" };
      return { state: replaceTask(state, deleted), op: { kind: "delete", task: deleted } };
    }
    case "clear":
      return { state: EMPTY_STATE, op: { kind: "clear", count: state.tasks.length } };
  }
}

function update(state: TodoState, params: TodoParams, fail: (message: string) => Applied): Applied {
  if (params.id === undefined) return fail("id required for update");
  const current = state.tasks.find((task) => task.id === params.id);
  if (!current) return fail(`#${params.id} not found`);
  const mutates =
    params.subject !== undefined ||
    params.description !== undefined ||
    params.activeForm !== undefined ||
    params.status !== undefined ||
    params.owner !== undefined ||
    params.metadata !== undefined ||
    (params.addBlockedBy?.length ?? 0) > 0 ||
    (params.removeBlockedBy?.length ?? 0) > 0;
  if (!mutates) return fail(`update requires at least one mutable field: ${MUTABLE_FIELDS}`);

  const status = params.status ?? current.status;
  if (status !== current.status && !TRANSITIONS[current.status].includes(status)) {
    return fail(`illegal transition ${current.status} → ${status}`);
  }

  const removed = new Set(params.removeBlockedBy ?? []);
  const blockedBy = (current.blockedBy ?? []).filter((dep) => !removed.has(dep));
  for (const dep of params.addBlockedBy ?? []) {
    if (dep === current.id) return fail(`cannot block #${current.id} on itself`);
    const problem = dependencyProblem(state, dep);
    if (problem) return fail(`addBlockedBy: ${problem}`);
    if (!blockedBy.includes(dep)) blockedBy.push(dep);
  }
  if ((params.addBlockedBy?.length ?? 0) > 0 && closesCycle(state.tasks, current.id, blockedBy)) {
    return fail("addBlockedBy would create a cycle in the blockedBy graph");
  }

  const updated: Task = { ...current, status };
  if (params.subject !== undefined) updated.subject = params.subject;
  if (params.description !== undefined) updated.description = params.description;
  if (params.activeForm !== undefined) updated.activeForm = params.activeForm;
  if (params.owner !== undefined) updated.owner = params.owner;
  if (blockedBy.length > 0) updated.blockedBy = blockedBy;
  else delete updated.blockedBy;
  const metadata = params.metadata === undefined ? current.metadata : mergeMetadata(current.metadata, params.metadata);
  if (metadata === undefined) delete updated.metadata;
  else updated.metadata = metadata;

  return {
    state: replaceTask(state, updated),
    op: { kind: "update", id: updated.id, from: current.status, to: status, changed: taskChanged(current, updated) },
  };
}

/** Why task [id] can't be a dependency, or null when it can. */
function dependencyProblem(state: TodoState, id: number): string | null {
  const task = state.tasks.find((candidate) => candidate.id === id);
  if (!task) return `#${id} not found`;
  return task.status === "deleted" ? `#${id} is deleted` : null;
}

/** Merges [changes] into [metadata], a null value removing its key; an empty result is no metadata. */
function mergeMetadata(
  metadata: Record<string, unknown> | undefined,
  changes: Record<string, unknown>,
): Record<string, unknown> | undefined {
  const merged = { ...metadata };
  for (const [key, value] of Object.entries(changes)) {
    if (value === null) delete merged[key];
    else merged[key] = value;
  }
  return Object.keys(merged).length > 0 ? merged : undefined;
}

function replaceTask(state: TodoState, task: Task): TodoState {
  return { tasks: state.tasks.map((candidate) => (candidate.id === task.id ? task : candidate)), nextId: state.nextId };
}

/**
 * Whether anything the model can see changed. Resending a task's values is a no-op reported as `No change`, so a model
 * that repeats a call doesn't read it as a fresh update. Metadata is compared as JSON, the form it is saved in.
 */
function taskChanged(before: Task, after: Task): boolean {
  return (
    before.subject !== after.subject ||
    before.status !== after.status ||
    before.description !== after.description ||
    before.activeForm !== after.activeForm ||
    before.owner !== after.owner ||
    (before.blockedBy ?? []).join() !== (after.blockedBy ?? []).join() ||
    JSON.stringify(before.metadata ?? null) !== JSON.stringify(after.metadata ?? null)
  );
}

/** Whether giving task [id] the dependencies [blockedBy] makes a task wait on itself through other tasks. */
export function closesCycle(tasks: readonly Task[], id: number, blockedBy: readonly number[]): boolean {
  const edges = new Map(tasks.map((task) => [task.id, task.id === id ? blockedBy : (task.blockedBy ?? [])]));
  const seen = new Set<number>();
  const pending = [...blockedBy];
  while (pending.length > 0) {
    const next = pending.pop() as number;
    if (next === id) return true;
    if (seen.has(next)) continue;
    seen.add(next);
    pending.push(...(edges.get(next) ?? []));
  }
  return false;
}

/** The tasks that wait on each task. */
function blocksOf(tasks: readonly Task[]): Map<number, number[]> {
  const blocks = new Map<number, number[]>();
  for (const task of tasks) {
    for (const dep of task.blockedBy ?? []) blocks.set(dep, [...(blocks.get(dep) ?? []), task.id]);
  }
  return blocks;
}

const ids = (list: readonly number[], separator: string): string => list.map((id) => `#${id}`).join(separator);

/** The text the model reads for [op], with [state] the list after it. */
export function formatContent(op: Op, state: TodoState): string {
  switch (op.kind) {
    case "create":
      return `Created #${op.task.id}: ${sanitize(op.task.subject)} (pending)`;
    case "update":
      if (!op.changed) return `No change: #${op.id} already matches the requested values (status: ${op.to})`;
      return op.from === op.to ? `Updated #${op.id}` : `Updated #${op.id} (${op.from} → ${op.to})`;
    case "delete":
      return `Deleted #${op.task.id}: ${sanitize(op.task.subject)}`;
    case "clear":
      return `Cleared ${op.count} tasks`;
    case "list": {
      const shown = state.tasks.filter(
        (task) => (op.includeDeleted || task.status !== "deleted") && (op.status === undefined || task.status === op.status),
      );
      return shown.length === 0 ? "No tasks" : shown.map(listLine).join("\n");
    }
    case "get":
      return getLines(op.task, state);
    case "error":
      return `Error: ${op.message}`;
  }
}

function listLine(task: Task): string {
  const form = task.status === "in_progress" && task.activeForm ? ` (${sanitize(task.activeForm)})` : "";
  const blocked = task.blockedBy?.length ? ` ⛓ ${ids(task.blockedBy, ",")}` : "";
  return `[${task.status}] #${task.id} ${sanitize(task.subject)}${form}${blocked}`;
}

function getLines(task: Task, state: TodoState): string {
  const blocks = blocksOf(state.tasks).get(task.id) ?? [];
  const lines = [`#${task.id} [${task.status}] ${sanitize(task.subject)}`];
  if (task.description) lines.push(`  description: ${sanitize(task.description)}`);
  if (task.activeForm) lines.push(`  activeForm: ${sanitize(task.activeForm)}`);
  if (task.blockedBy?.length) lines.push(`  blockedBy: ${ids(task.blockedBy, ", ")}`);
  if (blocks.length > 0) lines.push(`  blocks: ${ids(blocks, ", ")}`);
  if (task.owner) lines.push(`  owner: ${sanitize(task.owner)}`);
  return lines.join("\n");
}

/** The tool result for [params], with the whole list after the call as `details`. */
export function toolResult(
  params: TodoParams,
  applied: Applied,
): { content: [{ type: "text"; text: string }]; details: TodoDetails } {
  const details: TodoDetails = {
    action: params.action,
    params: params as Record<string, unknown>,
    tasks: applied.state.tasks,
    nextId: applied.state.nextId,
  };
  if (applied.op.kind === "error") details.error = applied.op.message;
  return { content: [{ type: "text", text: formatContent(applied.op, applied.state) }], details };
}

function isDetails(value: unknown): value is TodoDetails {
  if (typeof value !== "object" || value === null) return false;
  const details = value as Partial<TodoDetails>;
  return Array.isArray(details.tasks) && typeof details.nextId === "number";
}

/**
 * The list as the last call on a session branch left it. Entries of [entryType], saved with every change, win; a
 * branch without any, such as one rpiv-todo worked on, falls back to the last `todo` tool result's `details`.
 */
export function replayBranch(entries: Iterable<unknown>, entryType: string): TodoState {
  let fromEntries: TodoDetails | undefined;
  let fromResults: TodoDetails | undefined;
  for (const entry of entries) {
    const e = entry as {
      type?: string;
      customType?: string;
      data?: unknown;
      message?: { role?: string; toolName?: string; details?: unknown };
    };
    if (e.type === "custom" && e.customType === entryType && isDetails(e.data)) fromEntries = e.data;
    const message = e.type === "message" ? e.message : undefined;
    if (message?.role === "toolResult" && message.toolName === TOOL_NAME && isDetails(message.details)) {
      fromResults = message.details;
    }
  }
  const details = fromEntries ?? fromResults;
  return details ? { tasks: details.tasks.map((task) => ({ ...task })), nextId: details.nextId } : EMPTY_STATE;
}

/** The tasks a person sees: all but deleted ones. */
export function visibleTasks(state: TodoState): Task[] {
  return state.tasks.filter((task) => task.status !== "deleted");
}

/** The theme colors the widget uses, as Pi's `Theme.fg` takes them. */
export type WidgetColor = "accent" | "dim" | "muted" | "success" | "text" | "warning";

export interface WidgetTheme {
  fg(color: WidgetColor, text: string): string;
}

/**
 * SGR strikethrough. Pi's `theme.strikethrough` goes through chalk, which styles nothing when Pi's output is a pipe,
 * as it is in RPC mode, so the escape is written out here.
 */
const strike = (text: string): string => `\u001b[9m${text}\u001b[29m`;

const GLYPH: Record<Exclude<TaskStatus, "deleted">, [WidgetColor, string]> = {
  pending: ["dim", "○"],
  in_progress: ["warning", "◐"],
  completed: ["success", "✓"],
};

/**
 * The widget's lines, as rpiv-todo's overlay draws them: a heading with the done count, then one line per task with
 * its status, a struck-through subject once done, the activeForm while in progress, and the tasks it waits on. Task ids
 * show only when some task waits on another, as only then do they mean something. Null when no task is left to show.
 */
export function widgetLines(state: TodoState, theme: WidgetTheme): string[] | null {
  const tasks = visibleTasks(state);
  if (tasks.length === 0) return null;
  const done = tasks.filter((task) => task.status === "completed").length;
  const active = done < tasks.length;
  const headingColor: WidgetColor = active ? "accent" : "dim";
  const lines = [theme.fg(headingColor, `${active ? "●" : "○"} Todos (${done}/${tasks.length})`)];
  const showIds = tasks.some((task) => (task.blockedBy?.length ?? 0) > 0);
  tasks.forEach((task, index) => {
    const branch = theme.fg("dim", index === tasks.length - 1 ? "└─" : "├─");
    lines.push(`${branch} ${taskLine(task as Task & { status: Exclude<TaskStatus, "deleted"> }, theme, showIds)}`);
  });
  return lines;
}

function taskLine(task: Task & { status: Exclude<TaskStatus, "deleted"> }, theme: WidgetTheme, showId: boolean): string {
  const [glyphColor, glyph] = GLYPH[task.status];
  const subjectColor: WidgetColor =
    task.status === "in_progress" ? "accent" : task.status === "completed" ? "muted" : "text";
  const subject = theme.fg(subjectColor, sanitize(task.subject));
  let line = theme.fg(glyphColor, glyph);
  if (showId) line += ` ${theme.fg("dim", `#${task.id}`)}`;
  line += ` ${task.status === "completed" ? strike(subject) : subject}`;
  if (task.status === "in_progress" && task.activeForm) line += ` ${theme.fg("muted", `(${sanitize(task.activeForm)})`)}`;
  if (task.blockedBy?.length) line += ` ${theme.fg("muted", `⛓ ${ids(task.blockedBy, ",")}`)}`;
  return line;
}

export const NO_TODOS = "No todos yet. Ask the agent to add some!";

/** `/todos`: the tasks grouped by status under a count, as rpiv-todo's command lists them; null when there are none. */
export function commandText(state: TodoState): string | null {
  const tasks = visibleTasks(state);
  if (tasks.length === 0) return null;
  const of = (status: TaskStatus): Task[] => tasks.filter((task) => task.status === status);
  const [pending, inProgress, completed] = [of("pending"), of("in_progress"), of("completed")];
  const counts: string[] = [];
  if (completed.length > 0) counts.push(`${completed.length}/${tasks.length} completed`);
  if (inProgress.length > 0) counts.push(`${inProgress.length} in progress`);
  if (pending.length > 0) counts.push(`${pending.length} pending`);
  const lines = [counts.join(" · ")];
  const section = (title: string, group: Task[], glyph: string): void => {
    if (group.length === 0) return;
    lines.push(title);
    for (const task of group) lines.push(commandLine(task, glyph));
  };
  section("── Pending ──", pending, "○");
  section("── In Progress ──", inProgress, "◐");
  section("── Completed ──", completed, "✓");
  return lines.join("\n");
}

function commandLine(task: Task, glyph: string): string {
  const form = task.status === "in_progress" && task.activeForm ? ` (${sanitize(task.activeForm)})` : "";
  const blocked = task.blockedBy?.length ? `    ⛓ ${ids(task.blockedBy, ",")}` : "";
  return `  ${glyph} #${task.id} ${sanitize(task.subject)}${form}${blocked}`;
}

/**
 * Task text without terminal controls, as rpiv-todo shows it: escape sequences go whole, line breaks and tabs become
 * spaces so a field stays on its line, and bidi controls go so a field can't reorder the text around it.
 */
export function sanitize(value: string): string {
  // oxlint-disable no-control-regex -- matching terminal control characters is the point of this function.
  return value
    .replace(/(?:\u001b\[|\u009b)[0-?]*[ -/]*[@-~]/g, "")
    .replace(/(?:\u001b\]|\u009d)[^\u0007\u009c\u001b]*(?:\u0007|\u009c|\u001b\\)?/g, "")
    .replace(/\u001b./g, "")
    .replace(/[\u2028\u2029]/g, " ")
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, (character) => ("\n\r\t".includes(character) ? " " : ""))
    .replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, "");
  // oxlint-enable no-control-regex
}
