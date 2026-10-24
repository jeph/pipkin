/**
 * Pipkin test gate: a permission extension for testing Pipkin subagents on an emulator. Every `bash`, `edit` and
 * `write` call, the subagents' included, asks "Yes", "Yes, for this chat" or "No" in the chat's question card. "Yes,
 * for this chat" allows that tool for the rest of the session, for the parent and its subagents alike; "No" refuses
 * the call.
 *
 * Install it by copying this folder to `~/.pipkin-dev/pi/extensions/pipkin-test-gate/` and reloading the chat. Its
 * switches are files in `pipkin-test-gate/` inside Pi's config folder (`~/.pipkin-dev/pi/pipkin-test-gate/` for
 * Pipkin Dev), read at every call, so they change without a reload:
 *
 *   gate-subagent   when present, `subagent` calls ask too (as does the environment variable
 *                   PIPKIN_TEST_GATE_SUBAGENT=1).
 *   delay-ms        a number of milliseconds to wait before asking, so questions from parallel calls overlap.
 */
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { type ExtensionAPI, getAgentDir } from "@earendil-works/pi-coding-agent";

const YES = "Yes";
const FOR_CHAT = "Yes, for this chat";
const NO = "No";

const GATED = ["bash", "edit", "write"];

/** Tools allowed "for this chat", by session id; on `globalThis`, so a `/reload` keeps them. */
const GRANTS = Symbol.for("pipkin.test-gate.grants");

function grants(sessionId: string): Set<string> {
  const global = globalThis as { [GRANTS]?: Map<string, Set<string>> };
  global[GRANTS] ??= new Map();
  let granted = global[GRANTS].get(sessionId);
  if (!granted) {
    granted = new Set();
    global[GRANTS].set(sessionId, granted);
  }
  return granted;
}

function switchFile(name: string): string {
  return join(getAgentDir(), "pipkin-test-gate", name);
}

function gatesSubagent(): boolean {
  return process.env.PIPKIN_TEST_GATE_SUBAGENT === "1" || existsSync(switchFile("gate-subagent"));
}

function delayMs(): number {
  try {
    const value = Number(readFileSync(switchFile("delay-ms"), "utf8").trim());
    return Number.isFinite(value) && value > 0 ? value : 0;
  } catch {
    return 0;
  }
}

function wait(ms: number, signal: AbortSignal | undefined): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  const timer = setTimeout(resolve, ms);
  signal?.addEventListener(
    "abort",
    () => {
      clearTimeout(timer);
      resolve();
    },
    { once: true },
  );
  return promise;
}

/** A short line naming what the call does. */
function summary(input: unknown): string {
  const fields = new Map<string, unknown>(typeof input === "object" && input !== null ? Object.entries(input) : []);
  const value = ["command", "path", "description"].map((key) => fields.get(key)).find((field) => typeof field === "string");
  if (typeof value !== "string") return "";
  return value.length > 120 ? `${value.slice(0, 119)}…` : value;
}

export default function pipkinTestGate(pi: ExtensionAPI): void {
  pi.on("tool_call", async (event, ctx) => {
    const gated = GATED.includes(event.toolName) || (event.toolName === "subagent" && gatesSubagent());
    if (!gated) return undefined;
    const granted = grants(ctx.sessionManager.getSessionId());
    if (granted.has(event.toolName)) return undefined;
    const delay = delayMs();
    if (delay > 0) await wait(delay, ctx.signal);
    if (!ctx.hasUI) return { block: true, reason: `Pipkin test gate: no one to ask about ${event.toolName}` };
    const what = summary(event.input);
    const answer = await ctx.ui.select(`Allow ${event.toolName}${what ? `: ${what}` : ""}?`, [YES, FOR_CHAT, NO]);
    if (answer === FOR_CHAT) granted.add(event.toolName);
    if (answer === YES || answer === FOR_CHAT) return undefined;
    return { block: true, reason: `The user refused this ${event.toolName} call` };
  });
}
