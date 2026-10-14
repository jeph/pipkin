package io.jeph.pipkin.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedLayoutTest {
  @Test
  fun everyKindOfTabAndPaneItemReadsBackAsSaved() {
    val layout = SavedLayout(
      tabs = listOf(
        SavedTab.Terminal("/w", number = 2, name = "Build \"watch\""),
        SavedTab.Terminal("/w", number = 3, name = null),
        SavedTab.File("/w", "src/naïve file.kt"),
        SavedTab.Commit("/w", sha = "0123abcd", subject = "fix: quote \"this\"\nand that", authorTime = 1_760_000_000L),
      ),
      panes = mapOf(Pane.Primary to SavedPaneItem.Tab(3), Pane.Secondary to SavedPaneItem.Chat("/w", "/s/a.jsonl")),
    )

    assertEquals(layout, savedLayoutOf(layout.toJson()))
  }

  @Test
  fun aNewChatKeepsItsFolderWithoutASessionFile() {
    val layout = SavedLayout(emptyList(), mapOf(Pane.Primary to SavedPaneItem.Chat("/w", sessionPath = null)))

    assertEquals(layout, savedLayoutOf(layout.toJson()))
  }

  @Test
  fun aPaneShowingNothingStaysEmpty() {
    val layout = SavedLayout(listOf(SavedTab.File("/w", "a")), mapOf(Pane.Secondary to SavedPaneItem.Tab(0)))

    assertEquals(layout, savedLayoutOf(layout.toJson()))
  }

  @Test
  fun aPaneShowingATabThatIsNotSavedDropsTheLayout() {
    val json = SavedLayout(listOf(SavedTab.File("/w", "a")), mapOf(Pane.Primary to SavedPaneItem.Tab(1))).toJson()

    assertNull(savedLayoutOf(json))
  }

  @Test
  fun anUnknownTabKindDropsTheLayout() {
    assertNull(savedLayoutOf("""{"tabs":[{"kind":"browser","cwd":"/w"}],"panes":{}}"""))
  }

  @Test
  fun damagedJsonDropsTheLayout() {
    assertNull(savedLayoutOf("""{"tabs":[{"kind":"file","cwd":"/w"""))
    assertNull(savedLayoutOf("""{"panes":{}}"""))
  }
}
