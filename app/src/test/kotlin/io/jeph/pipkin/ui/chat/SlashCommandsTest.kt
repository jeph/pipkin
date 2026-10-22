package io.jeph.pipkin.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandsTest {
  private fun typed(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

  private fun query(text: String, cursor: Int = text.length) = slashQuery(text, TextRange(cursor))

  private fun names(commands: List<SlashCommand>) = commands.map { it.name }

  private fun commands(vararg names: String) = names.map { SlashCommand(it, null, CommandSource.Extension) }

  @Test
  fun `query is the name typed after a leading slash up to the cursor`() {
    assertEquals("", query("/"))
    assertEquals("skill:de", query("/skill:de"))
    assertEquals("com", query("/compact", cursor = 4))
  }

  @Test
  fun `menu closes once the name is done or the slash is not at the start`() {
    assertNull(query("/compact "))
    assertNull(query("/compact focus on tests"))
    assertNull(query("fix this /comp"))
    assertNull(query(" /comp"))
    assertNull(query("/a/b"))
    assertNull(query("/", cursor = 0))
    assertNull(slashQuery("/compact", TextRange(1, 4)))
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
    assertEquals(typed("/compact "), insertCommand("/com", cursor = 4, "compact"))
    assertEquals(typed("/compact now", cursor = 8), insertCommand("/com now", cursor = 4, "compact"))
  }

  @Test
  fun `menu lists each name once, preferring built-ins and then Pi's first command`() {
    val pi = listOf(
      SlashCommand("hello", "extension", CommandSource.Extension),
      SlashCommand("compact", "template", CommandSource.Prompt),
      SlashCommand("hello", "template", CommandSource.Prompt),
      SlashCommand("skill:hello", "skill", CommandSource.Skill),
    )
    val menu = menuCommands(pi)

    assertEquals(BuiltinCommand.entries.map { it.command } + listOf("hello", "skill:hello"), names(menu))
    val compact = menu.single { it.name == "compact" }
    assertEquals(BuiltinCommand.Compact, compact.builtin)
    assertEquals(CommandSource.Builtin, compact.source)
    assertEquals("extension", menu.single { it.name == "hello" }.description)
    assertEquals(CommandSource.Extension, menu.single { it.name == "hello" }.source)
  }

  @Test
  fun `a command's source is read from Pi's name for it, and an unknown or missing one is null`() {
    assertEquals(CommandSource.Extension, CommandSource.of("extension"))
    assertEquals(CommandSource.Prompt, CommandSource.of("prompt"))
    assertEquals(CommandSource.Skill, CommandSource.of("skill"))
    assertNull(CommandSource.of("mcp"))
    assertNull(CommandSource.of(""))
    // Pipkin's own commands never come from Pi.
    assertNull(CommandSource.of("builtin"))
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
