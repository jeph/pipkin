/**
 * Pipkin test model: a scripted model for testing Pipkin subagents on an emulator, with no network or API key.
 *
 * Install it by copying this folder to `~/.pipkin-dev/pi/extensions/pipkin-test-model/` (for example through a
 * Pipkin terminal tab), reload the chat, and pick the model `pipkin-test/scripted`. It registers the provider
 * `pipkin-test` with one model, `scripted`, built on pi-ai's faux provider.
 *
 * The parent's reply depends on a keyword in the user's latest message. Each keyword makes the parent call tools in
 * its first reply, then sum up the results in the next:
 *
 *   sa-one-explore            one `subagent` call to Explore, which reads README.md (or reports it missing) and answers.
 *   sa-two-general-bash       two `subagent` calls in one reply; each General runs `bash` `sleep N; echo x` (N = 4, 6).
 *   sa-six                    six General calls in one reply, each running `sleep N; echo done-K` (N = 4 + K): four
 *                             run and two wait as Queued.
 *   sa-eight-fail             eight General calls; number 5 hits a provider error, the others sleep and echo.
 *   sa-parent-bash-while-sub  the parent calls `bash` (`sleep 5; echo parent`) and one General `subagent` (which runs
 *                             `sleep 5; echo sub`) in the same reply.
 *   sa-long-output            a General subagent replies with about 70 KB of text, past the 50 KB result limit.
 *   sa-slow                   a General subagent runs `sleep 60`, then answers, quoting any message it was steered with.
 *   sa-ask                    a General subagent calls the tool `ask_user` if it has one (filling its parameters from
 *                             their schema), else says it has none.
 *   sa-general-edit           a General subagent writes `pipkin-subagent-test.txt` in the project with `write`.
 *   sa-explore-bash           an Explore subagent tries `bash`, which it doesn't have, and reports the error.
 *   sa-agent:NAME             one `subagent` call to the agent NAME (a user or project agent), which answers.
 *   sa-model:PROVIDER/ID      one General `subagent` call with `model` set to PROVIDER/ID, which answers.
 *
 * A message with no keyword gets a reply listing them. The parent gives each subagent a task starting with
 * "pipkin-test subagent: <keyword> #<n>.", which is how the model tells a subagent's turns from the parent's: a
 * conversation whose first user message holds that marker is a subagent's.
 *
 * With the environment variable PIPKIN_TEST_MODEL_FAST=1, every `sleep` lasts a tenth of a second, as the host tests
 * run it.
 */
import {
  type AssistantMessage,
  createFauxCore,
  fauxAssistantMessage,
  fauxToolCall,
  getCurrentTools,
  type Message,
  type SimpleStreamOptions,
  type Tool,
  type ToolCall,
  type TranscriptContext,
} from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const PROVIDER = "pipkin-test";
const MODEL = "scripted";
const MARK = "pipkin-test subagent:";

/** [seconds] for a `sleep`, or a tenth of a second for the host tests. */
function sleep(seconds: number): string {
  return `sleep ${process.env.PIPKIN_TEST_MODEL_FAST === "1" ? 0.1 : seconds}`;
}

const KEYWORDS = [
  "sa-one-explore",
  "sa-two-general-bash",
  "sa-six",
  "sa-eight-fail",
  "sa-parent-bash-while-sub",
  "sa-long-output",
  "sa-slow",
  "sa-ask",
  "sa-general-edit",
  "sa-explore-bash",
  "sa-agent:",
  "sa-model:",
];

/** One `subagent` call the parent makes. */
interface Call {
  agent: string;
  description: string;
  model?: string;
}

type Arguments = ToolCall["arguments"];

function text(message: Message | undefined): string {
  if (!message || message.role === "system") return "";
  if (typeof message.content === "string") return message.content;
  return message.content.map((block) => (block.type === "text" ? block.text : "")).join("");
}

function general(description: string): Call {
  return { agent: "General", description };
}

/** The keyword in [message], with its argument for `sa-agent:` and `sa-model:`. */
function keyword(message: string): { word: string; argument: string } | undefined {
  const match = /\bsa-[a-z-]+(?::(\S+))?/.exec(message);
  if (!match) return undefined;
  const word = match[0].includes(":") ? `${match[0].split(":")[0]}:` : match[0];
  return KEYWORDS.includes(word) ? { word, argument: match[1] ?? "" } : undefined;
}

/** The subagent calls the parent makes for [word]. */
function subagentCalls(word: string, argument: string): Call[] {
  switch (word) {
    case "sa-one-explore":
      return [{ agent: "Explore", description: "read the README" }];
    case "sa-two-general-bash":
      return [general("first sleeper"), general("second sleeper")];
    case "sa-six":
      return Array.from({ length: 6 }, (_, index) => general(`sleeper ${index + 1} of 6`));
    case "sa-eight-fail":
      return Array.from({ length: 8 }, (_, index) => general(index === 4 ? "failing run" : `sleeper ${index + 1} of 8`));
    case "sa-parent-bash-while-sub":
      return [general("sleep beside the parent")];
    case "sa-long-output":
      return [general("long report")];
    case "sa-slow":
      return [general("minute-long sleep")];
    case "sa-ask":
      return [general("ask the user")];
    case "sa-general-edit":
      return [general("write a test file")];
    case "sa-explore-bash":
      return [{ agent: "Explore", description: "try to run a command" }];
    case "sa-agent:":
      return [{ agent: argument, description: `run ${argument}` }];
    case "sa-model:":
      return [{ ...general(`use ${argument}`), model: argument }];
    default:
      return [];
  }
}

function parentTurn(messages: Message[]): AssistantMessage {
  const latest = messages.findLastIndex((message) => message.role === "user");
  const found = keyword(text(messages[latest]));
  if (!found) {
    return fauxAssistantMessage(`Pipkin test model. Send one of these keywords: ${KEYWORDS.join(", ")}.`);
  }
  const answered = messages.slice(latest + 1).some((message) => message.role === "assistant");
  if (answered) {
    const results = messages
      .slice(latest + 1)
      .filter((message) => message.role === "toolResult")
      .map((message) => `- ${message.toolName}${message.isError ? " (error)" : ""}: ${text(message).trim().slice(0, 200)}`);
    return fauxAssistantMessage(`Finished ${found.word}${found.argument}. Results:\n${results.join("\n")}`);
  }
  const calls: ToolCall[] = subagentCalls(found.word, found.argument).map((call, index) =>
    fauxToolCall("subagent", {
      agent: call.agent,
      description: call.description,
      task: `${MARK} ${found.word}${found.argument} #${index + 1}. Follow the Pipkin test script.`,
      ...(call.model ? { model: call.model } : {}),
    }),
  );
  if (found.word === "sa-parent-bash-while-sub") calls.unshift(fauxToolCall("bash", { command: `${sleep(5)}; echo parent` }));
  return fauxAssistantMessage(calls, { stopReason: "toolUse" });
}

/** A value of the type a parameter's [property] schema names. */
function sample(property: unknown): Arguments[string] {
  const type = typeof property === "object" && property !== null && "type" in property ? property.type : "string";
  if (type === "array") return ["Yes", "No"];
  if (type === "number" || type === "integer") return 1;
  if (type === "boolean") return true;
  if (type === "object") return {};
  return "Pipkin test question: which do you pick?";
}

/** Arguments for a tool from its parameter schema: a value of the right type for every property. */
function argumentsFor(tool: Tool): Arguments {
  const schema: unknown = tool.parameters;
  const properties =
    typeof schema === "object" && schema !== null && "properties" in schema && typeof schema.properties === "object"
      ? Object.entries(schema.properties ?? {})
      : [];
  return Object.fromEntries(properties.map(([name, property]) => [name, sample(property)]));
}

function subagentTurn(task: string, messages: Message[]): AssistantMessage {
  const found = keyword(task.slice(task.indexOf(MARK) + MARK.length));
  const number = Number(/#(\d+)/.exec(task)?.[1] ?? "1");
  const latest = messages.findLastIndex((message) => message.role === "user");
  const step = messages.slice(latest + 1).filter((message) => message.role === "assistant").length;
  const steer = latest > messages.findIndex((message) => message.role === "user") ? text(messages[latest]) : "";
  const lastResult = messages.findLast((message) => message.role === "toolResult");
  const reported = (): AssistantMessage => {
    const outcome = lastResult ? `${lastResult.isError ? "error" : "ok"}: ${text(lastResult).trim().slice(0, 500)}` : "no tool";
    return fauxAssistantMessage(`Subagent ${number} finished (${outcome})${steer ? `; steered with: ${steer}` : ""}.`);
  };
  const call = (name: string, args: Arguments): AssistantMessage =>
    step === 0 && !steer ? fauxAssistantMessage(fauxToolCall(name, args), { stopReason: "toolUse" }) : reported();
  switch (found?.word) {
    case "sa-one-explore":
      return call("read", { path: "README.md" });
    case "sa-two-general-bash":
      return call("bash", { command: `${sleep(2 + 2 * number)}; echo x` });
    case "sa-six":
      return call("bash", { command: `${sleep(4 + number)}; echo done-${number}` });
    case "sa-eight-fail":
      if (number === 5) throw new Error("Pipkin test model: scripted provider failure");
      return call("bash", { command: `${sleep(2 + number)}; echo done-${number}` });
    case "sa-parent-bash-while-sub":
      return call("bash", { command: `${sleep(5)}; echo sub` });
    case "sa-long-output":
      return fauxAssistantMessage(
        Array.from({ length: 1200 }, (_, line) => `Line ${line + 1} of a long subagent report, padded out to fill space.`).join("\n"),
      );
    case "sa-slow":
      return call("bash", { command: `${sleep(60)}; echo slept` });
    case "sa-ask": {
      const ask = getCurrentTools(messages).find((tool) => tool.name === "ask_user");
      if (!ask) return fauxAssistantMessage("Subagent has no ask_user tool.");
      return call("ask_user", argumentsFor(ask));
    }
    case "sa-general-edit":
      return call("write", { path: "pipkin-subagent-test.txt", content: "Written by a Pipkin test subagent.\n" });
    case "sa-explore-bash":
      return call("bash", { command: "echo should not run" });
    default:
      return fauxAssistantMessage(`Subagent ${number} done: ${task}`);
  }
}

function respond(context: TranscriptContext): AssistantMessage {
  const messages = context.messages;
  const first = text(messages.find((message) => message.role === "user"));
  return first.includes(MARK) ? subagentTurn(first, messages) : parentTurn(messages);
}

export default function pipkinTestModel(pi: ExtensionAPI): void {
  const core = createFauxCore({
    api: "pipkin-test-scripted",
    provider: PROVIDER,
    models: [{ id: MODEL, name: "Pipkin test (scripted)", reasoning: false, input: ["text"], contextWindow: 200_000 }],
  });
  pi.registerProvider(PROVIDER, {
    name: "Pipkin test",
    baseUrl: "http://127.0.0.1",
    apiKey: "pipkin-test",
    api: core.api,
    // The faux core answers each request with the next queued step, taken as the request starts; queuing one step
    // per request keeps parallel subagents' requests apart.
    streamSimple: (model, context, options?: SimpleStreamOptions) => {
      core.setResponses([(stepContext) => respond(stepContext)]);
      return core.streamSimple(model, context, options);
    },
    models: [
      {
        id: MODEL,
        name: "Pipkin test (scripted)",
        reasoning: false,
        input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        contextWindow: 200_000,
        maxTokens: 64_000,
      },
    ],
  });
}
