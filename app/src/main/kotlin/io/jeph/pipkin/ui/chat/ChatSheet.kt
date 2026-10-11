package io.jeph.pipkin.ui.chat

import org.json.JSONObject

/** The sheet or dialog the chat screen shows over the stream; at most one at a time. */
internal sealed interface ChatSheet {
  data object Actions : ChatSheet

  /** The model picker, its search starting at [query]. */
  data class Model(val query: String = "") : ChatSheet

  data object Thinking : ChatSheet

  data object Rename : ChatSheet

  data object ConfirmReload : ChatSheet

  data object ConfirmDelete : ChatSheet

  /** The detail sheet of the thinking block or tool call with this timeline key. */
  data class Tool(val key: String) : ChatSheet

  /** `/session`: Pi's `get_session_stats` data. */
  data class SessionInfo(val stats: JSONObject) : ChatSheet

  /** `/fork`: the user messages (`entryId`, `text`) a fork can start from. */
  data class Fork(val messages: List<JSONObject>) : ChatSheet
}
