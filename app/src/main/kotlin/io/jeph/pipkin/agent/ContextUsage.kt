package io.jeph.pipkin.agent

import org.json.JSONObject

/**
 * How full the chat's context window is, from Pi's `get_session_stats`.
 * [tokens] and [percent] are null when Pi can't tell: no model with a known window, or right after a
 * compaction until the next reply reports usage. [contextWindow] is null when no window is known.
 */
internal data class ContextUsage(
  val tokens: Long?,
  val contextWindow: Long?,
  val percent: Double?,
  /** The whole session's spend in USD, across every model used. */
  val cost: Double,
) {
  companion object {
    fun from(stats: JSONObject): ContextUsage {
      val usage = stats.optJSONObject("contextUsage")
      return ContextUsage(
        tokens = usage?.number("tokens")?.toLong(),
        contextWindow = usage?.number("contextWindow")?.toLong(),
        percent = usage?.number("percent")?.toDouble(),
        cost = stats.number("cost")?.toDouble() ?: 0.0,
      )
    }

    /** A JSON number, or null for a missing key or JSON `null`. */
    private fun JSONObject.number(key: String): Number? = opt(key) as? Number
  }
}
