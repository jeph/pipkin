package io.jeph.pipkin.agent

/**
 * How long a chat that is neither working nor shown keeps its Pi before [AgentRegistry] parks it. [Never] parks a chat
 * only to make room for another.
 */
internal enum class IdleStop(val millis: Long?) {
  OneMinute(60_000L),
  FiveMinutes(5 * 60_000L),
  FifteenMinutes(15 * 60_000L),
  ThirtyMinutes(30 * 60_000L),
  Never(null),
  ;

  companion object {
    val Default = FiveMinutes
  }
}
