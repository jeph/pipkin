package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.AssistantText
import io.jeph.pipkin.agent.TimelineItem.ExtensionNotice
import io.jeph.pipkin.agent.TimelineItem.User
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** `ctx.ui.notify` as timeline rows: levels, coalescing, and where saved notices go back on a reload. */
class ExtensionNoticeTimelineTest {
  private val saved = mutableListOf<List<SavedNotice>>()
  private val timeline = ChatTimeline(onNoticesChanged = { saved += it }) { 1_000L }

  private val items get() = timeline.state.value.items

  private companion object {
    /** An assistant message's content: one text block. */
    const val TEXT = """[{"type":"text","text":"Done"}]"""
  }

  @Test
  fun notifyShowsEachLevelTakesOtherTypesAsInfoAndSkipsBlankMessages() {
    notify("Careful", "warning")
    notify("Broke", "error")
    notify("Typeless", null)
    notify("Broke again", "error")
    notify("Debug", "debug")
    notify("  \n", "error")

    assertEquals(
      listOf(
        ExtensionNotice("en0", NoticeLevel.Warning, "Careful"),
        ExtensionNotice("en1", NoticeLevel.Error, "Broke"),
        ExtensionNotice("en2", NoticeLevel.Info, "Typeless"),
        ExtensionNotice("en3", NoticeLevel.Error, "Broke again"),
        ExtensionNotice("en4", NoticeLevel.Info, "Debug"),
      ),
      items,
    )
  }

  @Test
  fun anInfoNoticeReplacesTheInfoNoticeRightBeforeIt() {
    notify("Loading…", "info")
    notify("Loaded", "info")
    notify("Slow disk", "warning")
    notify("After the warning", "info")
    apply("""{"type":"message_start","message":{"role":"user","content":"go","timestamp":5}}""")
    notify("After the message", "info")

    assertEquals(
      listOf(
        ExtensionNotice("en0", NoticeLevel.Info, "Loaded"),
        ExtensionNotice("en1", NoticeLevel.Warning, "Slow disk"),
        ExtensionNotice("en2", NoticeLevel.Info, "After the warning"),
        User("m0", "go", emptyList(), 5),
        ExtensionNotice("en3", NoticeLevel.Info, "After the message"),
      ),
      items,
    )
  }

  @Test
  fun aReloadPutsEachNoticeBackWhereItShowedLive() {
    notify("Sandbox ready", "info")
    apply("""{"type":"agent_start"}""")
    apply("""{"type":"message_start","message":{"role":"user","content":"go","timestamp":10}}""")
    apply("""{"type":"message_end","message":{"role":"user","content":"go","timestamp":10}}""")
    notify("Blocked write to .env", "warning")
    apply("""{"type":"message_start","message":{"role":"assistant","content":[],"timestamp":20}}""")
    apply(
      """{"type":"message_end","message":{"role":"assistant","stopReason":"stop","timestamp":20,"content":$TEXT}}""",
    )
    notify("Checkpoint saved", "info")
    apply("""{"type":"agent_settled"}""")
    notify("Unknown preset", "error")
    // The next run's start hooks notify once agent_start opened its footer, but before its first message.
    apply("""{"type":"agent_start"}""")
    notify("Plan mode on", "warning")
    val live = items.dropLast(1).map { it::class to (it as? ExtensionNotice)?.message }

    timeline.seed(
      JSONArray(
        """[{"role":"user","content":"go","timestamp":10},
          {"role":"assistant","stopReason":"stop","timestamp":20,"durationMs":5,"content":$TEXT}]""",
      ),
    )

    assertEquals(live, items.map { it::class to (it as? ExtensionNotice)?.message })
    assertEquals(listOf("en0", "m0", "en1", "m1.0", "en2", "m1.footer", "en3", "en4"), items.map { it.key })
  }

  @Test
  fun restoredNoticesReplaceThePlacedOnesAndThoseWhoseMessageIsGoneComeFirst() {
    timeline.seed(JSONArray())
    notify("From the old session", "info")
    timeline.restoreNotices {
      listOf(
        SavedNotice(NoticeLevel.Warning, "Before the compaction", after = 5, afterRun = false),
        SavedNotice(NoticeLevel.Info, "After the kept message", after = 30, afterRun = true),
      )
    }
    assertEquals(emptyList<TimelineItem>(), items)

    // After a compaction Pi's history opens with the summary, newer than the messages it kept.
    timeline.seed(
      JSONArray(
        """[{"role":"compactionSummary","summary":"s","tokensBefore":1,"timestamp":40},
          {"role":"user","content":"kept","timestamp":30}]""",
      ),
    )

    assertEquals(
      listOf(
        ExtensionNotice("en0", NoticeLevel.Warning, "Before the compaction"),
        User("m0", "kept", emptyList(), 30),
        ExtensionNotice("en1", NoticeLevel.Info, "After the kept message"),
      ),
      items,
    )
  }

  @Test
  fun aNoticePiSendsWhileStartingFollowsTheHistoryItLoads() {
    val before = SavedNotice(NoticeLevel.Info, "Before any message", after = null, afterRun = true)
    val afterUser = SavedNotice(NoticeLevel.Warning, "After the question", after = 10, afterRun = false)
    timeline.restoreNotices { listOf(before, afterUser) }
    notify("Loaded (startup)", "info")
    // Until the history comes, only the notices placed in it are saved.
    assertEquals(listOf(listOf(before, afterUser)), saved)

    timeline.seed(
      JSONArray(
        """[{"role":"user","content":"go","timestamp":10},
          {"role":"assistant","stopReason":"stop","timestamp":20,"content":$TEXT}]""",
      ),
    )

    assertEquals(
      listOf("Before any message", "go", "After the question", "Done", "Loaded (startup)"),
      items.map { (it as? ExtensionNotice)?.message ?: (it as? User)?.text ?: (it as AssistantText).markdown },
    )
    assertEquals(
      listOf(before, afterUser, SavedNotice(NoticeLevel.Info, "Loaded (startup)", after = 20, afterRun = true)),
      saved.last(),
    )
  }

  @Test
  fun noticesFromASessionPiMovesToWaitForItsHistory() {
    timeline.seed(JSONArray("""[{"role":"user","content":"old","timestamp":10}]"""))
    notify("In the old session", "info")
    timeline.awaitHistory()
    notify("Forked (fork)", "info")

    assertEquals(
      listOf(
        ExtensionNotice("en0", NoticeLevel.Info, "In the old session"),
        ExtensionNotice("en1", NoticeLevel.Info, "Forked (fork)"),
      ),
      items.filterIsInstance<ExtensionNotice>(),
    )
    assertEquals(listOf(SavedNotice(NoticeLevel.Info, "In the old session", after = 10, afterRun = true)), saved.last())

    // The fork ends before the old message, so the notice that followed it is not carried over.
    timeline.restoreNotices { placed -> placed.filter { it.after == null } }
    timeline.seed(JSONArray("""[{"role":"user","content":"new","timestamp":5}]"""))

    assertEquals(
      listOf(User("m0", "new", emptyList(), 5), ExtensionNotice("en0", NoticeLevel.Info, "Forked (fork)")),
      items,
    )
    assertEquals(listOf(SavedNotice(NoticeLevel.Info, "Forked (fork)", after = 5, afterRun = true)), saved.last())
  }

  @Test
  fun aCancelledMoveKeepsItsNoticesWhereTheyCame() {
    timeline.seed(JSONArray("""[{"role":"user","content":"go","timestamp":10}]"""))
    timeline.awaitHistory()
    notify("Fork cancelled", "warning")
    timeline.keepHistory()
    apply("""{"type":"message_start","message":{"role":"user","content":"next","timestamp":30}}""")
    apply("""{"type":"message_end","message":{"role":"user","content":"next","timestamp":30}}""")

    timeline.seed(
      JSONArray("""[{"role":"user","content":"go","timestamp":10},{"role":"user","content":"next","timestamp":30}]"""),
    )

    assertEquals(
      listOf("go", "Fork cancelled", "next"),
      items.map { (it as? ExtensionNotice)?.message ?: (it as User).text },
    )
    assertEquals(listOf(SavedNotice(NoticeLevel.Warning, "Fork cancelled", after = 10, afterRun = true)), saved.last())
  }

  @Test
  fun everyNoticeChangeIsReportedForSaving() {
    apply("""{"type":"message_start","message":{"role":"user","content":"go","timestamp":7}}""")
    notify("Loading", null)
    notify("Loaded", null)
    notify(" ", null)

    assertEquals(
      listOf(
        listOf(SavedNotice(NoticeLevel.Info, "Loading", after = 7, afterRun = false)),
        listOf(SavedNotice(NoticeLevel.Info, "Loaded", after = 7, afterRun = false)),
      ),
      saved,
    )
  }

  private fun apply(json: String) = timeline.apply(JSONObject(json))

  private fun notify(message: String, notifyType: String?) = timeline.apply(
    JSONObject().put("type", "extension_ui_request").put("id", "n").put("method", "notify").put("message", message)
      .apply { notifyType?.let { put("notifyType", it) } },
  )
}
