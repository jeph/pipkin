package io.jeph.pipkin.ui.chat

import androidx.annotation.StringRes
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.jeph.pipkin.R

/**
 * Pi's interactive commands that Pipkin runs itself, since Pi's RPC mode would send them to the model as
 * text. Like in Pi's terminal UI they win over an extension command of the same name. [immediate]
 * commands take no argument and run as soon as they are picked from the menu.
 */
internal enum class BuiltinCommand(val command: String, @StringRes val description: Int, val immediate: Boolean) {
  Settings("settings", R.string.command_settings, immediate = true),
  Model("model", R.string.command_model, immediate = false),
  Thinking("thinking", R.string.command_thinking, immediate = false),
  Export("export", R.string.command_export, immediate = false),
  Copy("copy", R.string.command_copy, immediate = true),
  Name("name", R.string.command_name, immediate = false),
  Session("session", R.string.command_session, immediate = true),
  Fork("fork", R.string.command_fork, immediate = true),
  Clone("clone", R.string.command_clone, immediate = true),
  Login("login", R.string.command_login, immediate = true),
  Logout("logout", R.string.command_logout, immediate = true),
  New("new", R.string.command_new, immediate = true),
  Compact("compact", R.string.command_compact, immediate = false),
  Resume("resume", R.string.command_resume, immediate = true),
  Reload("reload", R.string.command_reload, immediate = true),
  ;

  companion object {
    fun named(name: String): BuiltinCommand? = entries.firstOrNull { it.command == name }
  }
}

/** One row of the slash menu: a Pipkin [builtin], or a Pi command (extension, prompt template or skill). */
internal data class SlashCommand(val name: String, val description: String?, val builtin: BuiltinCommand? = null)

/** A sent `/name argument` that names a [BuiltinCommand]. */
internal data class BuiltinInvocation(val command: BuiltinCommand, val argument: String)

private val NOT_IN_QUERY = Regex("""[/\s"']""")

/**
 * The command name typed so far when the cursor sits in a `/name` at the very start of the draft, else
 * null. Pi only expands commands that open the message, so a slash later in the text is plain text.
 */
internal fun slashQuery(text: CharSequence, selection: TextRange): String? {
  val cursor = selection.end
  if (!selection.collapsed || cursor < 1 || !text.startsWith("/")) return null
  val query = text.substring(1, cursor)
  return query.takeUnless { NOT_IN_QUERY.containsMatchIn(it) }
}

/**
 * Every menu row, built-ins first, then [pi] in Pi's order (extension commands, prompt templates,
 * skills). One row per name: a built-in hides a Pi command of the same name, as in Pi's terminal UI, and
 * among Pi's the first is the one Pi runs, since it tries extension commands before templates.
 */
internal fun menuCommands(pi: List<SlashCommand>): List<SlashCommand> =
  (BuiltinCommand.entries.map { SlashCommand(it.command, null, it) } + pi).distinctBy { it.name }

/**
 * [commands] matching [query], best first: exact name, name prefix, a word start inside the name (after
 * `-`, `_`, `:` or `.`, so `deb` finds `skill:debug`), substring, then the letters in order. Ties sort
 * by name; an empty query keeps the given order.
 */
internal fun rankCommands(commands: List<SlashCommand>, query: String): List<SlashCommand> {
  val needle = query.trim().lowercase()
  if (needle.isEmpty()) return commands
  return commands
    .mapNotNull { command -> matchTier(command.name.lowercase(), needle)?.let { command to it } }
    .sortedWith(
      compareBy<Pair<SlashCommand, Int>> {
        it.second
      }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name },
    )
    .map { it.first }
}

private fun matchTier(name: String, query: String): Int? = when {
  name == query -> 0
  name.startsWith(query) -> 1
  name.indices.any { it > 0 && !name[it - 1].isLetterOrDigit() && name.startsWith(query, it) } -> 2
  name.contains(query) -> 3
  isSubsequence(query, name) -> 4
  else -> null
}

private fun isSubsequence(query: String, name: String): Boolean {
  var next = 0
  for (char in name) {
    if (next < query.length && query[next] == char) next++
  }
  return next == query.length
}

/** Replaces the `/query` typed up to [cursor] with `/name`, adding a space when it ends the draft for arguments. */
internal fun insertCommand(text: CharSequence, cursor: Int, name: String): TextFieldValue {
  val after = text.substring(cursor)
  val inserted = "/$name" + if (after.isEmpty()) " " else ""
  return TextFieldValue(inserted + after, TextRange(inserted.length))
}

/** The [BuiltinCommand] a draft runs when sent, with the text after its name; null for anything else. */
internal fun parseBuiltin(text: String): BuiltinInvocation? {
  val trimmed = text.trim()
  if (!trimmed.startsWith("/")) return null
  val end = trimmed.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: trimmed.length
  val command = BuiltinCommand.named(trimmed.substring(1, end)) ?: return null
  return BuiltinInvocation(command, trimmed.substring(end).trim())
}
