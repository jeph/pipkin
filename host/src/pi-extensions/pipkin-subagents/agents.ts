/**
 * Agent definitions: the bundled Explore and General, the user's in `<agentDir>/agents/*.md`, and a trusted project's
 * in `.pi/agents/*.md`. A definition is Markdown with frontmatter (`name`, `description`, `tools`, `model`,
 * `thinking`); its body is added to the subagent's system prompt. Later sources override earlier ones by name, so a
 * project can replace a user agent and either can replace a bundled one.
 */
import { readdirSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";
import { CONFIG_DIR_NAME, parseFrontmatter } from "@earendil-works/pi-coding-agent";

/** Pi's thinking levels, which a definition's `thinking` and the tool's `thinking` take. */
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

/** Which of the parent's tools an agent gets: all of them, the read-only ones, or the ones named. */
export type ToolChoice = "all" | "read-only" | readonly string[];

export interface AgentDefinition {
  name: string;
  description: string;
  tools: ToolChoice;
  /** `provider/id`; the parent's model when unset. */
  model?: string;
  thinking?: ThinkingLevel;
  /** Added to the subagent's system prompt. */
  prompt: string;
  /** The file it came from; undefined for the bundled ones. */
  file?: string;
}

/** A definition file that couldn't be read, under the name its file suggests, so calling that name says why. */
export interface BrokenDefinition {
  name: string;
  file: string;
  reason: string;
}

export interface Agents {
  byName: Map<string, AgentDefinition>;
  broken: BrokenDefinition[];
}

/** Tools a subagent never gets: they act on the parent's own subagents, to-do list and background tasks. */
export const PARENT_ONLY_TOOLS: readonly string[] = ["subagent", "todo", "background"];

/** Explore's built-in tools; extension tools marked read-only join them. */
const EXPLORE_TOOLS = ["read", "grep", "find", "ls"];

export const BUNDLED_AGENTS: readonly AgentDefinition[] = [
  {
    name: "Explore",
    description:
      "Reads and searches the project without changing anything, and reports what it found. Use it for research: finding where something is defined or used, tracing a flow, summarising files.",
    tools: "read-only",
    prompt:
      "You are a subagent exploring a codebase for another agent. You can read and search files but not change them or run commands. Answer the task with the facts you found, citing file paths and line numbers, as concisely as the task allows: your final reply is all the other agent sees.",
  },
  {
    name: "General",
    description:
      "Has the same tools as you and works on a self-contained task, including changing files and running commands, then reports back.",
    tools: "all",
    prompt:
      "You are a subagent working on a task another agent handed you. Do the task completely, then reply with what you did and anything the other agent needs to know: your final reply is all it sees.",
  },
];

/** Every agent: bundled, then the user's, then the project's when [projectTrusted]. */
export function loadAgents(agentDir: string, cwd: string, projectTrusted: boolean): Agents {
  const agents: Agents = { byName: new Map(BUNDLED_AGENTS.map((agent) => [agent.name, agent])), broken: [] };
  readFolder(join(agentDir, "agents"), agents);
  if (projectTrusted) readFolder(join(cwd, CONFIG_DIR_NAME, "agents"), agents);
  return agents;
}

function readFolder(dir: string, agents: Agents): void {
  let names: string[];
  try {
    names = readdirSync(dir).filter((name) => name.endsWith(".md")).toSorted();
  } catch {
    return;
  }
  for (const name of names) {
    const file = join(dir, name);
    try {
      const agent = parseAgent(file, readFileSync(file, "utf8"));
      agents.byName.set(agent.name, agent);
      agents.broken = agents.broken.filter((broken) => broken.name !== agent.name);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      agents.broken.push({ name: basename(name, ".md"), file, reason });
    }
  }
}

/** The definition in [content], read from [file]; throws saying what's wrong with it. */
export function parseAgent(file: string, content: string): AgentDefinition {
  const { frontmatter, body } = parseFrontmatter(content);
  const name = optionalText(frontmatter, "name") ?? basename(file, ".md");
  if (!/^[\w.-]+$/.test(name)) throw new Error(`name "${name}" may only hold letters, digits, ".", "_" and "-"`);
  const agent: AgentDefinition = {
    name,
    description: optionalText(frontmatter, "description") ?? "",
    tools: toolChoice(frontmatter.tools),
    prompt: body.trim(),
    file,
  };
  const model = optionalText(frontmatter, "model");
  if (model !== undefined) {
    if (!model.includes("/")) throw new Error(`model "${model}" must be written provider/id`);
    agent.model = model;
  }
  const thinking = optionalText(frontmatter, "thinking");
  if (thinking !== undefined) {
    if (!isThinkingLevel(thinking)) throw new Error(`thinking must be one of ${THINKING_LEVELS.join(", ")}`);
    agent.thinking = thinking;
  }
  return agent;
}

export function isThinkingLevel(value: string): value is ThinkingLevel {
  return (THINKING_LEVELS as readonly string[]).includes(value);
}

function optionalText(frontmatter: Record<string, unknown>, key: string): string | undefined {
  const value = frontmatter[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") throw new Error(`${key} must be text`);
  return value.trim() || undefined;
}

/** `tools` as a YAML list or a comma-separated line; missing means all of the parent's. */
function toolChoice(value: unknown): ToolChoice {
  if (value === undefined || value === null) return "all";
  const names = typeof value === "string" ? value.split(",") : value;
  if (!Array.isArray(names) || !names.every((name) => typeof name === "string")) {
    throw new Error("tools must be a list of tool names");
  }
  return names.map((name: string) => name.trim()).filter((name) => name.length > 0);
}

/** The error for an agent name that isn't defined: why, when its file is broken, or the names there are. */
export function unknownAgentText(agents: Agents, name: string): string {
  const broken = agents.broken.find((entry) => entry.name === name);
  if (broken) return `The agent definition ${broken.file} could not be read: ${broken.reason}`;
  const available = [...agents.byName.keys()].join(", ");
  const unreadable = agents.broken.map((entry) => `${entry.file} (${entry.reason})`);
  const note = unreadable.length > 0 ? ` These definition files could not be read: ${unreadable.join("; ")}.` : "";
  return `Unknown agent "${name}". Available agents: ${available}.${note}`;
}

/** Facts about one of the parent's callable tools that choosing an agent's tools needs. */
export interface ToolFacts {
  name: string;
  readOnly: boolean;
}

/** The names, in the parent's order, of the parent's [callable] tools that [agent] gets. */
export function agentToolNames(agent: AgentDefinition, callable: readonly ToolFacts[]): string[] {
  const allowed = callable.filter((tool) => !PARENT_ONLY_TOOLS.includes(tool.name));
  const choice = agent.tools;
  if (choice === "all") return allowed.map((tool) => tool.name);
  if (choice === "read-only") {
    return allowed.filter((tool) => EXPLORE_TOOLS.includes(tool.name) || tool.readOnly).map((tool) => tool.name);
  }
  return allowed.filter((tool) => choice.includes(tool.name)).map((tool) => tool.name);
}
