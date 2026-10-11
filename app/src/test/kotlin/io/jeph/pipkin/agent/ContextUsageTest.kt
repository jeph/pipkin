package io.jeph.pipkin.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageTest {
  @Test
  fun readsTokensWindowPercentAndCost() {
    val stats = JSONObject(
      """{"cost":0.45,"contextUsage":{"tokens":60000,"contextWindow":200000,"percent":30}}""",
    )

    assertEquals(ContextUsage(60_000, 200_000, 30.0, 0.45), ContextUsage.from(stats))
  }

  @Test
  fun justCompactedKeepsTheWindowButNotAZeroCount() {
    val stats = JSONObject("""{"cost":0,"contextUsage":{"tokens":null,"contextWindow":272000,"percent":null}}""")

    assertEquals(ContextUsage(null, 272_000, null, 0.0), ContextUsage.from(stats))
  }

  @Test
  fun noModelMeansNoContextData() {
    assertEquals(ContextUsage(null, null, null, 0.0), ContextUsage.from(JSONObject("""{"cost":0}""")))
  }
}
