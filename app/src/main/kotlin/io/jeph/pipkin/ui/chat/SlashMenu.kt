package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.RpcError
import io.jeph.pipkin.agent.commands
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** The highlighted row for one query; a new query highlights its best match again. */
private data class Highlight(val query: String, val index: Int)

/**
 * Paseo's slash-command menu for one chat: while the draft starts with `/name` it lists Pipkin's
 * built-in commands and Pi's own (extension commands, prompt templates, skills), best match nearest
 * the composer. Picking inserts the command, or runs a built-in that takes no argument.
 */
internal class SlashMenu(private val controller: ChatController) {
  /** Pi's commands from the last `get_commands`, null until one answered. */
  var piCommands by mutableStateOf<List<SlashCommand>?>(null)
    private set

  var loading by mutableStateOf(false)
    private set

  private var highlight by mutableStateOf(Highlight("", 0))

  /** The name typed after `/` while the menu is open, else null. */
  val query: String? get() = queryIn(controller.draft.text, controller.draft.selection)

  /**
   * While the menu is open a newline typed at the cursor picks the highlighted command instead, since soft keyboards
   * type Enter as text before any key handler sees it. [onRan] follows a built-in that ran.
   */
  fun enterPicks(onRan: () -> Unit) = InputTransformation {
    val query = queryIn(originalText, originalSelection) ?: return@InputTransformation
    val cursor = originalSelection.end
    val typed = asCharSequence()
    val enter = typed.length == originalText.length + 1 &&
      typed.toString() == originalText.substring(0, cursor) + "\n" + originalText.substring(cursor)
    if (!enter) return@InputTransformation
    val option = options(query).let { it.getOrNull(highlighted(query, it.size)) } ?: return@InputTransformation
    revertAllChanges()
    // The draft takes no other edit while this one is in progress, so the pick applies once it is done.
    controller.scope.launch { if (select(option)) onRan() }
  }

  private fun queryIn(text: CharSequence, selection: TextRange): String? =
    if (controller.composerFocused) slashQuery(text, selection) else null

  fun options(query: String): List<SlashCommand> = rankCommands(menuCommands(piCommands.orEmpty()), query)

  fun highlighted(query: String, count: Int): Int =
    if (highlight.query == query) highlight.index.coerceIn(0, (count - 1).coerceAtLeast(0)) else 0

  suspend fun refresh() {
    loading = true
    try {
      piCommands = controller.process.commands().map { command ->
        SlashCommand(command.optString("name"), command.optString("description").ifBlank { null })
      }
    } catch (_: RpcError) {
      // Pi is still starting or gone; the built-ins still work and the menu asks again once Pi is ready.
    } finally {
      loading = false
    }
  }

  /** Inserts [command] into the draft, or runs it when it is a built-in without arguments; true when it ran. */
  fun select(command: SlashCommand): Boolean {
    val draft = controller.draft
    val builtin = command.builtin
    if (builtin != null && builtin.immediate) {
      draft.clearText()
      controller.builtins.run(BuiltinInvocation(builtin, ""))
      return true
    }
    val inserted = insertCommand(draft.text, draft.selection.end, command.name)
    draft.edit {
      replace(0, length, inserted.text)
      selection = inserted.selection
    }
    return false
  }

  /**
   * A hardware key while the menu is open: Up and Down move the highlight (the list grows upwards),
   * Tab and Enter pick it, Escape clears the draft. [onRan] follows a built-in that ran.
   */
  fun onKey(event: KeyEvent, onRan: () -> Unit): Boolean {
    val query = query
    if (query == null || event.type != KeyEventType.KeyDown) return false
    val options = options(query)
    val option = options.getOrNull(highlighted(query, options.size))
    when {
      event.key == Key.Escape -> controller.draft.clearText()
      option == null -> return false
      event.key == Key.DirectionUp -> move(query, options.size, step = 1)
      event.key == Key.DirectionDown -> move(query, options.size, step = -1)
      event.key == Key.Tab || event.key == Key.Enter || event.key == Key.NumPadEnter -> if (select(option)) onRan()
      else -> return false
    }
    return true
  }

  private fun move(query: String, count: Int, step: Int) {
    highlight = Highlight(query, (highlighted(query, count) + step).mod(count))
  }
}

/**
 * The menu above the composer: a raised card up to 220dp tall whose rows stack upwards from the best match, which is
 * tinted cobalt. [onRan] follows a picked built-in that ran.
 */
@Composable
internal fun SlashCommandMenu(menu: SlashMenu, query: String, onRan: () -> Unit, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val options = menu.options(query)
  val highlighted = menu.highlighted(query, options.size)
  Box(
    modifier =
      modifier
        .shadow(8.dp, MENU_SHAPE, ambientColor = SHADOW, spotColor = SHADOW)
        .clip(MENU_SHAPE)
        .background(colors.surface1)
        .heightIn(max = 220.dp),
  ) {
    if (options.isEmpty()) {
      val text = if (menu.loading) R.string.chat_commands_loading else R.string.chat_commands_empty
      MenuRow(onClick = null) {
        Text(stringResource(text), color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
      }
      return@Box
    }
    val listState = rememberLazyListState()
    LaunchedEffect(options) { listState.scrollToItem(0) }
    LaunchedEffect(highlighted) {
      val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
      if (highlighted !in visible) listState.scrollToItem(highlighted)
    }
    LazyColumn(
      state = listState,
      reverseLayout = true,
      contentPadding = PaddingValues(PipkinTokens.Spacing.s6),
    ) {
      itemsIndexed(options, key = { _, option -> option.name }) { index, option ->
        CommandRow(option, highlighted = index == highlighted, onClick = { if (menu.select(option)) onRan() })
      }
    }
  }
}

@Composable
private fun CommandRow(command: SlashCommand, highlighted: Boolean, onClick: () -> Unit) {
  val colors = pipkinColors
  val description = command.builtin?.let { stringResource(it.description) } ?: command.description
  MenuRow(
    onClick = onClick,
    modifier = if (highlighted) Modifier.background(colors.accentSoft, ROW_SHAPE) else Modifier,
  ) {
    Text(
      text = "/${command.name}",
      color = if (highlighted) colors.accent else colors.foreground,
      fontFamily = PipkinFonts.mono,
      fontSize = 12.5.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (description != null) {
      Text(
        text = description,
        modifier = Modifier.weight(1f),
        color = colors.foregroundMuted,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun MenuRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 36.dp)
      .clip(ROW_SHAPE)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 9.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    content = content,
  )
}

private val MENU_SHAPE = RoundedCornerShape(20.dp)
private val ROW_SHAPE = RoundedCornerShape(16.dp)

/** Black at 20%, for a card floating over the chat. */
private val SHADOW = Color.Black.copy(alpha = 0.2f)
