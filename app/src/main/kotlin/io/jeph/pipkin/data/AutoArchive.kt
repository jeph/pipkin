package io.jeph.pipkin.data

import io.jeph.pipkin.host.SessionInfo
import java.util.concurrent.TimeUnit

/** The setting in Settings › Chats that archives chats nobody touched for a number of days. */
internal object AutoArchive {
  /** The days a chat may go untouched that the setting offers. */
  val DAYS = 1..30

  const val DEFAULT_DAYS = 7

  /** How often auto archive asks for a fresh chat list while Pipkin is on screen, so chats go stale on time. */
  val SWEEP_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1)

  /**
   * Those of [sessions] untouched for at least [days] at [now]: no message from the user or the agent (Pi's
   * [SessionInfo.modified]), and no touch in [touched], for that long. [kept], such as chats on screen or working, never
   * count.
   */
  fun stale(
    sessions: List<SessionInfo>,
    touched: Map<String, Long>,
    kept: Set<String>,
    now: Long,
    days: Int,
  ): List<SessionInfo> {
    val cutoff = now - TimeUnit.DAYS.toMillis(days.toLong())
    return sessions.filter { session ->
      session.path !in kept && maxOf(session.modified, touched[session.path] ?: 0L) <= cutoff
    }
  }
}
