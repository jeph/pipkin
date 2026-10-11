package io.jeph.pipkin.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandsTest {
  private fun typed(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

  private fun names(commands: List<SlashCommand>) = commands.map { it.name }

  private fun commands(vararg names: String) = names.map { SlashCommand(it, null) }

  @Test
  fun `query is the name typed after a leading slash up to the cursor`() {
    assertEquals("", slashQuery(typed("/")))
    assertEquals("skill:de", slashQuery(typed("/skill:de")))
    assertEquals("com", slashQuery(typed("/compact", cursor = 4)))
  }

  @Test
  fun `menu closes once the name is done or the slash is not at the start`() {
    assertNull(slashQuery(typed("/compact ")))
    assertNull(slashQuery(typed("/compact focus on tests")))
    assertNull(slashQuery(typed("fix this /comp")))
    assertNull(slashQuery(typed(" /comp")))
    assertNull(slashQuery(typed("/a/b")))
    assertNull(slashQuery(typed("/", cursor = 0)))
    assertNull(slashQuery(TextFieldValue("/compact", TextRange(1, 4))))
  }

  @Test
  fun `ranking puts exact, prefix, word start, substring, then scattered letters`() {
    val ranked = rankCommands(commands("xdebugx", "d-e-b", "skill:debug", "debug-tests", "debug"), "debug")
    assertEquals(listOf("debug", "debug-tests", "skill:debug", "xdebugx"), names(ranked))
    assertEquals(listOf("d-e-b"), names(rankCommands(commands("d-e-b", "model"), "deb")))
  }

  @Test
  fun `ranking ignores case, breaks ties by name and keeps the given order for an empty query`() {
    assertEquals(listOf("compact", "Compare"), names(rankCommands(commands("Compare", "compact"), "COMP")))
    assertEquals(listOf("zeta", "alpha"), names(rankCommands(commands("zeta", "alpha"), "  ")))
    assertEquals(emptyList<String>(), names(rankCommands(commands("model"), "xyz")))
  }

  @Test
  fun `inserting replaces the typed name and adds a space only at the end`() {
    assertEquals(typed("/compact "), insertCommand(typed("/com"), "compact"))
    assertEquals(typed("/compact now", cursor = 8), insertCommand(typed("/com now", cursor = 4), "compact"))
  }

  @Test
  fun `menu lists each name once, preferring built-ins and then Pi's first command`() {
    val pi = listOf(
      SlashCommand("hello", "extension"),
      SlashCommand("compact", "template"),
      SlashCommand("hello", "template"),
      SlashCommand("skill:hello", "skill"),
    )
    val menu = menuCommands(pi)

    assertEquals(BuiltinCommand.entries.map { it.command } + listOf("hello", "skill:hello"), names(menu))
    assertEquals(BuiltinCommand.Compact, menu.single { it.name == "compact" }.builtin)
    assertEquals("extension", menu.single { it.name == "hello" }.description)
  }

  @Test
  fun `a sent builtin carries the trimmed text after its name`() {
    assertEquals(BuiltinInvocation(BuiltinCommand.Compact, ""), parseBuiltin("  /compact  "))
    assertEquals(BuiltinInvocation(BuiltinCommand.Name, "my  chat"), parseBuiltin("/name my  chat\n"))
    assertEquals(BuiltinInvocation(BuiltinCommand.Thinking, "high"), parseBuiltin("/thinking\nhigh"))
  }

  @Test
  fun `pi commands and plain text are not builtins`() {
    assertNull(parseBuiltin("/skill:debug fix it"))
    assertNull(parseBuiltin("/compacting"))
    assertNull(parseBuiltin("please /compact"))
    assertNull(parseBuiltin("/"))
  }
}
