package io.jeph.pipkin.ui.chat

import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.agent.ToolStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCardTextTest {
  @Test
  fun bashKeepsTheOutputUnderTheCommand() {
    assertEquals("$ ls\na\nb", tool("bash", """{"command":"ls"}""", "a\nb").cardText())
    assertEquals("$ ls", tool("bash", """{"command":"ls"}""", null).cardText())
  }

  @Test
  fun editCopiesEveryReplacementAsDiffLinesThenTheResult() {
    val args = """{"path":"a.kt","edits":[{"oldText":"x\ny","newText":"z"},{"oldText":"p","newText":""}]}"""
    assertEquals("- x\n- y\n+ z\n\n- p\n+ \n\nEdited", tool("edit", args, "Edited").cardText())
  }

  @Test
  fun fileToolsCopyTheBodyWithoutThePath() {
    assertEquals("body", tool("read", """{"path":"a.kt"}""", "body").cardText())
    assertEquals("fun a() {}\n\nWrote", tool("write", """{"path":"a.kt","content":"fun a() {}"}""", "Wrote").cardText())
  }

  @Test
  fun emptySectionsLeaveNothingToCopy() {
    assertEquals("", tool("mystery", "{}", "").cardText())
    assertEquals("{\"q\": 1}", tool("mystery", """{"q":1}""", null).cardText())
  }

  private fun tool(name: String, args: String, output: String?) =
    TimelineItem.ToolCall("m0.0", "c1", name, JSONObject(args), output, isError = false, status = ToolStatus.Done)
}
