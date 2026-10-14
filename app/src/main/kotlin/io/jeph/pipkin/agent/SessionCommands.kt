package io.jeph.pipkin.agent

import io.jeph.pipkin.host.objects
import org.json.JSONObject

/** Pi's `get_commands`: extension commands, prompt templates and skills (named `skill:<name>`). */
internal suspend fun AgentProcess.commands(): List<JSONObject> = call("get_commands").optJSONArray("commands").objects()

/** Summarizes the context; this waits for the summary, which is an LLM call of its own. */
internal suspend fun AgentProcess.compact(instructions: String) {
  val params = JSONObject()
  if (instructions.isNotBlank()) params.put("customInstructions", instructions)
  call("compact", params, timeoutMillis = null)
}

/** The last assistant message's text, or null before Pi answered. */
internal suspend fun AgentProcess.lastAssistantText(): String? = call("get_last_assistant_text").opt("text") as? String

/** Writes the session as HTML to [outputPath] (Pi picks a name in the cwd when blank); returns the path. */
internal suspend fun AgentProcess.exportHtml(outputPath: String): String {
  val params = JSONObject()
  if (outputPath.isNotBlank()) params.put("outputPath", outputPath)
  return call("export_html", params).optString("path")
}

/** `get_session_stats` data: message counts, `tokens`, `cost` and `contextUsage`. */
internal suspend fun AgentProcess.sessionStats(): JSONObject = call("get_session_stats")

/** The user messages a fork can start from (`entryId`, `text`), oldest first; skills show as typed. */
internal suspend fun AgentProcess.forkMessages(): List<JSONObject> =
  call("get_fork_messages").optJSONArray("messages").objects().onEach {
    it.put("text", typedText(it.optString("text")))
  }

/**
 * Moves this process to a new session that ends before [entryId]; returns that message as typed for the
 * composer, or null when an extension cancelled the fork. Waits without a deadline: Pi answers after the extensions'
 * `session_before_fork` handlers, which can wait on the user.
 */
internal suspend fun AgentProcess.fork(entryId: String): String? {
  val data = call("fork", JSONObject().put("entryId", entryId), timeoutMillis = null)
  if (data.optBoolean("cancelled")) return null
  reloadSession()
  return typedText(data.optString("text"))
}

/** Moves this process to a copy of the session; false when an extension cancelled it. Waits as [fork] does. */
internal suspend fun AgentProcess.cloneSession(): Boolean {
  if (call("clone", timeoutMillis = null).optBoolean("cancelled")) return false
  reloadSession()
  return true
}
