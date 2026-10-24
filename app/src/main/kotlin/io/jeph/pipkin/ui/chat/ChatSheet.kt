package io.jeph.pipkin.ui.chat

import org.json.JSONObject

/** The sheet or dialog the chat screen shows over the stream; at most one at a time. */
internal sealed interface ChatSheet {
  /** The model picker, its search starting at [query], with the thinking levels under it. */
  data class Model(val query: String = "") : ChatSheet

  data object Rename : ChatSheet

  data object ConfirmReload : ChatSheet

  /** Archiving would stop the run Pi is working on. */
  data object ConfirmArchive : ChatSheet

  /** The detail sheet of the thinking block or tool call with this timeline key. */
  data class Tool(val key: String) : ChatSheet

  /** `/session`: Pi's `get_session_stats` data. */
  data class SessionInfo(val stats: JSONObject) : ChatSheet

  /** The whole of the extension widget with this key. */
  data class Widget(val key: String) : ChatSheet

  /** The chat's subagents, from the Subagents tab. */
  data object Subagents : ChatSheet

  /** `/fork`: the user messages (`entryId`, `text`) a fork can start from. */
  data class Fork(val messages: List<JSONObject>) : ChatSheet
}
