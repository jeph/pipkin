/**
 * Pipkin todo: the `todo` tool of @juicesharp/rpiv-todo (see ./todo.ts) with the list shown as an extension widget,
 * which Pipkin draws as a tab on the composer. Each session keeps its own list, saved as a custom entry on its branch
 * with every change, so the list follows `/resume`, `/tree` and compaction, and a restarted Pi replays it.
 */
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import {
  EMPTY_STATE,
  NO_TODOS,
  PROMPT_GUIDELINES,
  PROMPT_SNIPPET,
  TOOL_DESCRIPTION,
  TOOL_LABEL,
  TOOL_NAME,
  TodoParams,
  type TodoState,
  applyTodo,
  changesState,
  commandText,
  replayBranch,
  toolResult,
  widgetLines,
} from "./todo.js";

/** The widget's key, which Pipkin names its tab after. */
export const WIDGET_KEY = "todos";

/** The custom entry each change saves the list in. */
export const STATE_ENTRY = "pipkin-todo-state";

export default function pipkinTodo(pi: ExtensionAPI): void {
  /** Each session's list, by session id: a Pi process can run more than one session, such as a subagent's. */
  const lists = new Map<string, TodoState>();
  const listOf = (ctx: ExtensionContext): TodoState => lists.get(ctx.sessionManager.getSessionId()) ?? EMPTY_STATE;

  /** Only a session with a UI shows the widget; another session's list must not replace it. */
  const show = (ctx: ExtensionContext): void => {
    if (!ctx.hasUI) return;
    ctx.ui.setWidget(WIDGET_KEY, widgetLines(listOf(ctx), ctx.ui.theme) ?? undefined);
  };

  const replay = (ctx: ExtensionContext): void => {
    lists.set(ctx.sessionManager.getSessionId(), replayBranch(ctx.sessionManager.getBranch(), STATE_ENTRY));
    show(ctx);
  };

  const register = (): void => {
    pi.registerTool({
      name: TOOL_NAME,
      label: TOOL_LABEL,
      description: TOOL_DESCRIPTION,
      promptSnippet: PROMPT_SNIPPET,
      promptGuidelines: PROMPT_GUIDELINES,
      parameters: TodoParams,
      async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
        const applied = applyTodo(listOf(ctx), params);
        const result = toolResult(params, applied);
        if (changesState(applied.op)) {
          lists.set(ctx.sessionManager.getSessionId(), applied.state);
          // Saved here as well as in the tool result: a call made through another tool, such as a codemode script,
          // never reaches the branch as a tool result of its own.
          pi.appendEntry(STATE_ENTRY, result.details);
          show(ctx);
        }
        return result;
      },
    });

    pi.registerCommand("todos", {
      description: "Show all todos on the current branch, grouped by status",
      handler: async (_args, ctx) => {
        ctx.ui.notify(commandText(listOf(ctx)) ?? NO_TODOS, "info");
      },
    });

    pi.on("session_tree", (_event, ctx) => replay(ctx));
    pi.on("session_compact", (_event, ctx) => replay(ctx));
    pi.on("session_shutdown", (_event, ctx) => {
      lists.delete(ctx.sessionManager.getSessionId());
    });
  };

  /**
   * Registers once every extension has loaded, and only when none of them has a `todo` tool: Pi refuses to start
   * when two extensions register one tool name at load, so a todo extension the user installed wins instead.
   */
  let owned: boolean | undefined;
  pi.on("session_start", (_event, ctx) => {
    if (owned === undefined) {
      owned = !pi.getAllTools().some((tool) => tool.name === TOOL_NAME);
      if (owned) register();
    }
    if (owned) replay(ctx);
  });
}
