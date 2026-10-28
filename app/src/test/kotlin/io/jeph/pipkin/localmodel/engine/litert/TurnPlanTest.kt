package io.jeph.pipkin.localmodel.engine.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnPlanTest {
  private val setup = ConversationSetup(system = "Be brief.", tools = listOf("""{"name":"read"}"""), temperature = null)
  private val user = ChatMessage.User(listOf("Read a.txt"))
  private val call = ChatMessage.Assistant("", listOf(ToolCall("call_ours", "read", """{"path":"a.txt"}""")))
  private val result = ChatMessage.ToolResult("call_ours", "read", "hello")

  private fun request(vararg messages: ChatMessage, with: ConversationSetup = setup) =
    ChatRequest(with, messages.toList(), stream = true, includeUsage = true, maxTokens = null, thinking = true)

  @Test
  fun `the first request starts a conversation seeded with its history`() {
    val earlier = ChatMessage.Assistant("Hi!", emptyList())

    assertEquals(
      TurnPlan.Rebuild(history = listOf(user, earlier), input = listOf(user)),
      planTurn(null, request(user, earlier, user)),
    )
  }

  @Test
  fun `a tool result that answers the live reply only sends the result`() {
    val live = Transcript(setup, listOf(user, call))

    assertEquals(TurnPlan.Continue(listOf(result)), planTurn(live, request(user, call, result)))
  }

  @Test
  fun `the results of parallel calls go together`() {
    val calls = ChatMessage.Assistant(
      "Reading both.",
      listOf(ToolCall("1", "read", """{"path":"a"}"""), ToolCall("2", "read", """{"path":"b"}""")),
    )
    val first = ChatMessage.ToolResult("1", "read", "a")
    val second = ChatMessage.ToolResult("2", "read", "b")
    val live = Transcript(setup, listOf(user, calls))

    assertEquals(TurnPlan.Continue(listOf(first, second)), planTurn(live, request(user, calls, first, second)))
  }

  @Test
  fun `the client's own call ids and trimmed text still match`() {
    val live = Transcript(setup, listOf(user, ChatMessage.Assistant("Done.\n", emptyList()), user, call))
    val echoed = listOf(
      user,
      ChatMessage.Assistant("Done.", emptyList()),
      user,
      ChatMessage.Assistant("", listOf(ToolCall("call_theirs", "read", """{"path":"a.txt"}"""))),
      ChatMessage.ToolResult("call_theirs", "read", "hello"),
    )

    assertEquals(TurnPlan.Continue(echoed.takeLast(1)), planTurn(live, request(*echoed.toTypedArray())))
  }

  @Test
  fun `a different system prompt, tool list or temperature starts over`() {
    val live = Transcript(setup, listOf(user, call))

    for (other in listOf(
      setup.copy(system = "Be verbose."),
      setup.copy(tools = emptyList()),
      setup.copy(temperature = 0.5),
    )) {
      assertEquals(
        TurnPlan.Rebuild(listOf(user, call), listOf(result)),
        planTurn(live, request(user, call, result, with = other)),
      )
    }
  }

  @Test
  fun `an edited or shortened history starts over`() {
    val live = Transcript(setup, listOf(user, call))
    val edited = ChatMessage.Assistant("", listOf(ToolCall("call_ours", "read", """{"path":"b.txt"}""")))

    assertEquals(TurnPlan.Rebuild(listOf(user, edited), listOf(result)), planTurn(live, request(user, edited, result)))
    assertEquals(TurnPlan.Rebuild(emptyList(), listOf(user)), planTurn(live, request(user)))
  }

  @Test
  fun `more than one new message starts over`() {
    val live = Transcript(setup, listOf(user, call))
    val steer = ChatMessage.User(listOf("Stop"))

    assertEquals(
      TurnPlan.Rebuild(listOf(user, call, result), listOf(steer)),
      planTurn(live, request(user, call, result, steer)),
    )
  }

  @Test
  fun `a request must end with a message to answer`() {
    assertThrows(BadRequestException::class.java) { planTurn(null, request(user, call)) }
    assertThrows(BadRequestException::class.java) { planTurn(null, request()) }
  }
}
