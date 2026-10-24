package io.jeph.pipkin.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A session in the host's `sessions` result. */
class SessionInfoTest {
  @Test
  fun theSubagentCountIsRead() {
    assertEquals(2, SessionInfo.from(session().put("subagents", 2)).subagents)
  }

  @Test
  fun aSessionFromAHostWithoutSubagentCountsHasNone() {
    assertEquals(0, SessionInfo.from(session()).subagents)
  }

  private fun session() = JSONObject().put("path", "/s/a.jsonl").put("id", "a").put("cwd", "/w")
}
