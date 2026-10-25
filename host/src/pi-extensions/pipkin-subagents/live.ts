/**
 * A subagent's transcript as the app follows it live. The app builds a chat's rows from Pi's RPC events, so it builds
 * a subagent's from the child's session events, which have the same shape. Each run keeps a log of those events,
 * trimmed to what still matters for the rows, so a screen opened mid-run first gets the log and then each event as
 * it comes: what it shows is what it would have shown open from the start.
 */
import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";

/** An event as the app gets it, with when the extension saw it, so the screen times the subagent's run as it went. */
export interface LiveEntry {
  at: number;
  event: Record<string, unknown>;
}

export interface LiveLog {
  entries: LiveEntry[];
  /** The start and updates of the message streaming now, which its end replaces. */
  streaming: Set<LiveEntry>;
  /** Each running tool's latest progress, by tool call id, which its end replaces. */
  progress: Map<string, LiveEntry>;
  /** The latest steering queue, which each change replaces. */
  queue: LiveEntry | undefined;
}

export function newLog(): LiveLog {
  return { entries: [], streaming: new Set(), progress: new Map(), queue: undefined };
}

/** The parts of a reply's stream the app builds rows from; the rest, such as `start` and `done`, it ignores. */
const STREAMED: Record<string, true> = {
  text_start: true,
  text_delta: true,
  text_end: true,
  thinking_start: true,
  thinking_delta: true,
  thinking_end: true,
  toolcall_start: true,
  toolcall_delta: true,
  toolcall_end: true,
};

const DELTAS: Record<string, true> = { text_delta: true, thinking_delta: true, toolcall_delta: true };

/**
 * [event] as the app needs it, or null for one it has no use for. A stream update leaves out the reply so far, which
 * Pi sends with every delta, and events that end a turn or a run leave out the messages they repeat. The system
 * prompt, which Pi's session events carry as a message, is left out, as the app shows none. Images' data is left out
 * too: the app shows none of them, and a tool that reads an image would send megabytes with every event that repeats
 * it.
 */
export function forApp(event: AgentSessionEvent): Record<string, unknown> | null {
  switch (event.type) {
    case "message_update": {
      const { partial: _partial, ...update } = event.assistantMessageEvent as { partial?: unknown; type: string };
      if (!STREAMED[update.type]) return null;
      return { type: event.type, assistantMessageEvent: update };
    }
    case "message_start":
    case "message_end":
      if (event.message.role === "system") return null;
      return withoutImageData(event) as Record<string, unknown>;
    case "turn_start":
    case "turn_end":
    case "agent_end":
      return { type: event.type };
    default:
      return withoutImageData(event) as Record<string, unknown>;
  }
}

/** Adds [entry] to [log], dropping or merging what it makes redundant. */
export function append(log: LiveLog, entry: LiveEntry): void {
  const event = entry.event;
  switch (event.type) {
    case "message_start":
      log.streaming = new Set([entry]);
      break;
    case "message_update":
      if (mergeDelta(log, entry)) return;
      log.streaming.add(entry);
      break;
    case "message_end":
      // The message's end holds all of it, so the app needs none of its stream.
      log.entries = log.entries.filter((kept) => !log.streaming.has(kept));
      log.streaming = new Set();
      break;
    case "tool_execution_update": {
      // Each update holds the tool's whole output so far.
      const previous = log.progress.get(String(event.toolCallId));
      if (previous) {
        previous.at = entry.at;
        previous.event = event;
        return;
      }
      log.progress.set(String(event.toolCallId), entry);
      break;
    }
    case "tool_execution_end": {
      const previous = log.progress.get(String(event.toolCallId));
      if (previous) log.entries = log.entries.filter((kept) => kept !== previous);
      log.progress.delete(String(event.toolCallId));
      break;
    }
    case "queue_update":
      if (log.queue) log.entries = log.entries.filter((kept) => kept !== log.queue);
      log.queue = entry;
      break;
    default:
  }
  log.entries.push(entry);
}

/** Adds a delta to the one before it when both continue the same block, as the app adds them up anyway. */
function mergeDelta(log: LiveLog, entry: LiveEntry): boolean {
  const last = log.entries.at(-1);
  if (!last || !log.streaming.has(last)) return false;
  const update = entry.event.assistantMessageEvent as Record<string, unknown>;
  const before = last.event.assistantMessageEvent as Record<string, unknown> | undefined;
  if (!before || !DELTAS[String(update.type)]) return false;
  if (before.type !== update.type || before.contentIndex !== update.contentIndex) return false;
  // A copy, since the event it came from was already sent as it was.
  last.event = { ...last.event, assistantMessageEvent: { ...before, delta: `${before.delta}${update.delta}` } };
  last.at = entry.at;
  return true;
}

/** [value] with every image block's data left out; parts with no image are the same objects, not copies. */
function withoutImageData(value: unknown): unknown {
  if (Array.isArray(value)) {
    const items = value.map(withoutImageData);
    return items.some((item, index) => item !== value[index]) ? items : value;
  }
  if (typeof value !== "object" || value === null) return value;
  const fields = value as Record<string, unknown>;
  if (fields.type === "image" && typeof fields.data === "string") {
    const { data: _data, ...rest } = fields;
    return rest;
  }
  let copy: Record<string, unknown> | undefined;
  for (const [key, field] of Object.entries(fields)) {
    const stripped = withoutImageData(field);
    if (stripped === field) continue;
    copy ??= { ...fields };
    copy[key] = stripped;
  }
  return copy ?? value;
}
