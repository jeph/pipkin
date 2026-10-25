/**
 * Pipkin subagents: the `subagent` tool, which hands a task to a second agent in the same Pi process and returns its
 * reply. The child's tools are stand-ins for the parent's (`./runs.ts`), so the parent's permission extensions decide
 * every call and their questions appear in the parent chat. The app follows the runs over Pipkin's channel
 * (`./state.ts`), and a subagent's transcript live while the run is listed (`./live.ts`); after that, it reads the
 * transcript from its file, `subagents/<parent id>/` beside the chat's.
 *
 * Runs belong to the Pi process, not to one load of the extension: Pi loads it again for each session and `/reload`,
 * and every load shares one registry. A run lasts as long as the parent's tool call, so the end of the parent's run,
 * by Stop, `/reload`, switching session or quitting, stops it through the tool's signal.
 */
import { StringEnum } from "@earendil-works/pi-ai";
import {
  type ExtensionAPI,
  type ExtensionContext,
  type ExtensionToolContext,
  getAgentDir,
} from "@earendil-works/pi-coding-agent";
import { type Static, Type } from "typebox";
import { type AgentDefinition, type Agents, agentToolNames, loadAgents, THINKING_LEVELS, unknownAgentText } from "./agents.js";
import { type RunRequest, runSubagent, startFailure } from "./runs.js";
import { dropFinished, MAX_RUNNING, registry, report } from "./state.js";

export const TOOL_NAME = "subagent";

const SubagentParams = Type.Object({
  agent: Type.String({ description: "The name of the agent to run, from the list in this tool's description" }),
  task: Type.String({
    description: "The full instructions. The subagent sees nothing of this conversation, so include everything it needs",
  }),
  description: Type.String({ description: 'Two to six words naming the task for the user, such as "auth redirect flow"' }),
  model: Type.Optional(
    Type.String({
      description:
        "A model as provider/id, only when the task needs another model than the agent's. Omit it to use the agent's model, or the chat's",
    }),
  ),
  thinking: Type.Optional(StringEnum(THINKING_LEVELS, { description: "Thinking level, only to override the agent's" })),
});

/** The tool's description, listing the agents [agents] holds as the session starts. */
export function toolDescription(agents: Agents): string {
  const list = [...agents.byName.values()].map((agent: AgentDefinition) =>
    agent.description ? `- ${agent.name}: ${agent.description}` : `- ${agent.name}`,
  );
  return [
    "Hand a self-contained task to a subagent: another agent with a fresh context that works on it with your tools and replies with its result, which this tool returns.",
    `Each call starts one subagent. To run several in parallel, call this tool several times in one reply; at most ${MAX_RUNNING} run at once in this chat, and further calls wait until one finishes.`,
    "Subagents can't start subagents, use the to-do list or run background tasks.",
    "",
    "Agents:",
    ...list,
  ].join("\n");
}

const PROMPT_SNIPPET = "Hand self-contained tasks to subagents, which can run in parallel and report back";

const PROMPT_GUIDELINES: string[] = [
  "Use `subagent` for self-contained work whose details you don't need in your own context, such as researching a part of the codebase. For independent tasks, call it several times in the same reply so they run in parallel.",
];

function resolveModel(ctx: ExtensionContext, spec: string | undefined): NonNullable<ExtensionContext["model"]> {
  if (spec === undefined) {
    if (!ctx.model) throw new Error("No model is selected for this chat, so the subagent has none either");
    return ctx.model;
  }
  const slash = spec.indexOf("/");
  const model = slash > 0 ? ctx.modelRegistry.find(spec.slice(0, slash), spec.slice(slash + 1)) : undefined;
  if (!model) throw new Error(`Unknown model "${spec}": give it as provider/id, as Pi lists it`);
  return model;
}

/** What [params] ask for, with every name resolved; throws saying what's wrong, as for an unknown agent or model. */
function settleRequest(
  pi: ExtensionAPI,
  toolCallId: string,
  params: Static<typeof SubagentParams>,
  ctx: ExtensionToolContext,
): RunRequest {
  const agents = loadAgents(getAgentDir(), ctx.cwd, ctx.isProjectTrusted());
  const agent = agents.byName.get(params.agent);
  if (!agent) throw new Error(unknownAgentText(agents, params.agent));
  // A blank model is no model: models sometimes send an empty string for an argument they mean to leave out.
  const model = resolveModel(ctx, params.model?.trim() || agent.model);
  const thinking = params.thinking ?? agent.thinking ?? ctx.thinkingLevel ?? pi.getThinkingLevel();
  const toolInfo = pi.getAllTools();
  const readOnly = new Set(toolInfo.filter((tool) => tool.annotations?.readOnlyHint).map((tool) => tool.name));
  const callable = ctx.tools.map((tool) => ({ name: tool.name, readOnly: readOnly.has(tool.name) }));
  return {
    toolCallId,
    agent,
    task: params.task,
    description: params.description,
    model,
    thinking,
    toolNames: agentToolNames(agent, callable),
    toolInfo,
  };
}

export default function pipkinSubagents(pi: ExtensionAPI): void {
  const state = registry();

  const register = (ctx: ExtensionContext): void => {
    pi.registerTool({
      name: TOOL_NAME,
      label: "Subagent",
      description: toolDescription(loadAgents(getAgentDir(), ctx.cwd, ctx.isProjectTrusted())),
      promptSnippet: PROMPT_SNIPPET,
      promptGuidelines: PROMPT_GUIDELINES,
      parameters: SubagentParams,
      // Code-mode scripts mustn't start subagents: only the model calls this tool.
      exposure: "model-only",
      // Several calls in one reply are the way to run subagents in parallel.
      executionMode: "parallel",
      async execute(toolCallId, params, signal, _onUpdate, toolCtx) {
        let request: RunRequest;
        try {
          request = settleRequest(pi, toolCallId, params, toolCtx);
        } catch (error) {
          return startFailure(params, error);
        }
        return runSubagent(state, request, signal, toolCtx);
      },
    });

    // Finished runs stay listed until the user's next message, so a person coming back sees how they ended.
    pi.on("input", (event, inputCtx) => {
      if (event.source === "extension") return undefined;
      dropFinished(state, inputCtx.sessionManager.getSessionId());
      report(state);
      return undefined;
    });
  };

  /**
   * Registers once every extension has loaded, and only when none of them has a `subagent` tool: Pi refuses to start
   * when two extensions register one tool name at load, so one the user installed wins instead. Standing aside, it
   * still tells the app, which then draws that tool's calls as ordinary tool rows rather than as subagents.
   */
  let owned: boolean | undefined;
  pi.on("session_start", (_event, ctx) => {
    if (owned === undefined) {
      owned = !pi.getAllTools().some((tool) => tool.name === TOOL_NAME);
      if (owned) register(ctx);
    }
    state.standingAside = !owned;
    state.current = owned ? ctx.sessionManager.getSessionId() : undefined;
    // A reload or a reconnected app may have missed the last list.
    report(state, true);
  });
}
