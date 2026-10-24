package io.jeph.pipkin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigatorTest {
  @Test
  fun anArchivedSubagentSurvivesBeingSavedWhateverItsPathHolds() {
    val stack = listOf(
      Destination.Main,
      Destination.Archive,
      Destination.ArchivedChat("/s/a b:c.jsonl"),
      Destination.ArchivedSubagent("/s/a b:c.jsonl", "call_1|fc_2"),
    )

    val restored = AppNavigator.Saver.restore(stack.map(AppNavigator::key))

    assertEquals(stack, restored?.stack?.value)
  }
}
