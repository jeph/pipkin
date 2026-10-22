package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ExtensionUiRequest
import io.jeph.pipkin.agent.UiAnswer
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay

/**
 * A Pi extension dialog (`docs/rpc-extension-ui.md`) as a card above the composer, the way Paseo shows an agent's
 * questions, so the chat and the composer stay usable while it waits. Only Submit answers, after an option is picked
 * or text is typed, so a stray tap never answers; Dismiss answers `cancelled`. [number] of [count] shows when
 * several dialogs came in together, and the seconds left when Pi answers the dialog itself after a timeout.
 */
@Composable
internal fun ExtensionUiCard(
  request: ExtensionUiRequest,
  number: Int,
  count: Int,
  onAnswer: (UiAnswer) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.lg)
  Box(modifier = modifier.fillMaxWidth().padding(horizontal = PipkinTokens.Spacing.s16), Alignment.TopCenter) {
    key(request.id) {
      val cardModifier = Modifier
        .widthIn(max = PipkinTokens.contentMaxWidth)
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface1)
        .border(1.dp, colors.border, shape)
        .padding(PipkinTokens.Spacing.s12)
      val header: @Composable () -> Unit = { CardHeader(request, number, count) }
      when (request.method) {
        "select" -> ChoiceCard(
          header = header,
          choices = request.options,
          onSubmit = { onAnswer(UiAnswer.Value(request.options[it])) },
          onDismiss = { onAnswer(UiAnswer.Cancelled) },
          modifier = cardModifier,
        )

        // Pi reads a cancelled `confirm` as No, so the card needs no Dismiss.
        "confirm" -> ChoiceCard(
          header = header,
          choices = listOf(stringResource(R.string.chat_yes), stringResource(R.string.chat_no)),
          onSubmit = { onAnswer(UiAnswer.Confirmed(it == 0)) },
          onDismiss = null,
          modifier = cardModifier,
        )

        "input", "editor" -> TextCard(request, header, onAnswer, cardModifier)
      }
    }
  }
}

/**
 * Stacks [header], [answers] and [buttons], with the buttons always whole. Pi puts no limit on a title or a message,
 * so when the header and answers together outgrow the card, each first keeps a floor: the header two lines, and the
 * answers two lines inside a field's padding when [answersScroll], or all of them when they can't scroll, as a one-line
 * field can't. What's left over is split evenly, and a part that needs less than its share gives the rest to the
 * other; a part shorter than its need scrolls inside it. When even the floors don't fit, answers that can't scroll stay
 * whole and the header gets the rest, and otherwise the two floors split the room the same way. The floors make the
 * card's min intrinsic height, so [ChatStack] can make room for them.
 */
@Composable
private fun CardLayout(
  header: @Composable () -> Unit,
  answers: @Composable () -> Unit,
  buttons: @Composable () -> Unit,
  answersScroll: Boolean,
  modifier: Modifier,
) {
  // Measured rather than counted in sp: Android scales large sizes less, so 2 × 24sp isn't two lines of 24sp text.
  val measurer = rememberTextMeasurer(cacheSize = 0)
  val style = LocalTextStyle.current.merge(TextStyle(fontSize = PipkinTokens.FontSize.base))
  val twoLines = remember(measurer, style) { measurer.measure("\n", style).size.height }
  val policy = remember(twoLines, answersScroll) { CardMeasurePolicy(twoLines, answersScroll) }
  Layout(contents = listOf(header, answers, buttons), modifier = modifier, measurePolicy = policy)
}

/** [twoLines] is the height of two lines of the card's text, the floor of the header and of scrolling answers. */
private class CardMeasurePolicy(private val twoLines: Int, private val answersScroll: Boolean) :
  MultiContentMeasurePolicy {
  override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
    val (header, answers, buttons) = measurables.map { it.single() }
    val width = constraints.maxWidth
    val gap = CARD_GAP.roundToPx()
    val fixedWidth = Constraints.fixedWidth(width)
    val buttonsPlaced = buttons.measure(fixedWidth)
    val headerNeed = header.maxIntrinsicHeight(width)
    val answersNeed = answers.maxIntrinsicHeight(width)
    val room = if (constraints.hasBoundedHeight) {
      (constraints.maxHeight - buttonsPlaced.height - 2 * gap).coerceAtLeast(0)
    } else {
      headerNeed + answersNeed
    }
    val headerHeight = headerHeight(headerNeed, answersNeed, room)
    val headerPlaced = header.measure(fixedWidth.copy(maxHeight = headerHeight))
    val answersPlaced = answers.measure(fixedWidth.copy(maxHeight = minOf(answersNeed, room - headerHeight)))
    val height = headerPlaced.height + answersPlaced.height + buttonsPlaced.height + 2 * gap
    return layout(width, height) {
      headerPlaced.place(0, 0)
      answersPlaced.place(0, headerPlaced.height + gap)
      buttonsPlaced.place(0, headerPlaced.height + answersPlaced.height + 2 * gap)
    }
  }

  override fun IntrinsicMeasureScope.minIntrinsicHeight(
    measurables: List<List<IntrinsicMeasurable>>,
    width: Int,
  ): Int {
    val (header, answers, buttons) = measurables.map { it.single() }
    val headerNeed = header.maxIntrinsicHeight(width)
    val answersNeed = answers.maxIntrinsicHeight(width)
    return headerFloor(headerNeed) + answersFloor(answersNeed) + buttons.maxIntrinsicHeight(width) +
      2 * CARD_GAP.roundToPx()
  }

  private fun headerFloor(need: Int) = minOf(need, twoLines)

  private fun Density.answersFloor(need: Int) =
    if (answersScroll) minOf(need, twoLines + 2 * PipkinTokens.Spacing.s12.roundToPx()) else need

  private fun Density.headerHeight(headerNeed: Int, answersNeed: Int, room: Int) =
    cardHeaderHeight(headerNeed, answersNeed, room, headerFloor(headerNeed), answersFloor(answersNeed), answersScroll)
}

/**
 * The header's height in [CardLayout]'s [room] for the header and the answers, from what each needs and its floor; the
 * answers get the rest. Answers that can't scroll ([answersScroll] false) have their need as their floor.
 */
internal fun cardHeaderHeight(
  headerNeed: Int,
  answersNeed: Int,
  room: Int,
  headerFloor: Int,
  answersFloor: Int,
  answersScroll: Boolean,
): Int {
  if (headerNeed + answersNeed <= room) return headerNeed
  if (headerFloor + answersFloor > room) {
    return if (answersScroll) split(room, headerFloor, answersFloor) else (room - answersNeed).coerceAtLeast(0)
  }
  return headerFloor + split(room - headerFloor - answersFloor, headerNeed - headerFloor, answersNeed - answersFloor)
}

/** The header's part of [room] split evenly with the answers, where a part that wants less gives the rest away. */
private fun split(room: Int, headerWants: Int, answersWants: Int) = when {
  headerWants <= room / 2 -> headerWants
  answersWants <= room / 2 -> room - answersWants
  else -> room / 2
}

/**
 * The title and a `confirm`'s message, scrolling together when [CardLayout] gives them less than their height, as Pi's
 * terminal shows them as one text above the options; beside them, the seconds left and the counter.
 */
@Composable
private fun CardHeader(request: ExtensionUiRequest, number: Int, count: Int) {
  val colors = pipkinColors
  val title = request.title ?: stringResource(
    when (request.method) {
      "select" -> R.string.chat_choose
      "confirm" -> R.string.chat_confirm_title
      else -> R.string.chat_input_title
    },
  )
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      Text(text = title, color = colors.foreground, fontSize = PipkinTokens.FontSize.base)
      request.message?.let { Text(text = it, color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.base) }
    }
    request.expiresAt?.let { SecondsLeft(it) }
    if (count > 1) {
      Text(
        text = stringResource(R.string.chat_dialog_counter, number, count),
        modifier = Modifier
          .border(1.dp, colors.border, RoundedCornerShape(PipkinTokens.Radius.md))
          .padding(horizontal = PipkinTokens.Spacing.s6, vertical = 1.dp),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
      )
    }
  }
}

/** Counts down to [expiresAt], when Pi stops waiting and answers the dialog with its default. */
@Composable
private fun SecondsLeft(expiresAt: Long) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(expiresAt) {
    while (now < expiresAt) {
      // Wakes on each whole second left, so the label changes when the count does.
      delay((expiresAt - now - 1) % MILLIS_PER_SECOND + 1)
      now = System.currentTimeMillis()
    }
  }
  val seconds = ((expiresAt - now + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(0)
  Text(
    text = stringResource(R.string.chat_dialog_seconds_left, seconds),
    modifier = Modifier.padding(top = 1.dp),
    color = pipkinColors.dotWarning,
    fontSize = PipkinTokens.FontSize.sm,
  )
}

/**
 * [choices] as radio rows, scrolling when [CardLayout] gives them less than their height; Submit sends the picked one's
 * index and is off until one is picked.
 */
@Composable
private fun ChoiceCard(
  header: @Composable () -> Unit,
  choices: List<String>,
  onSubmit: (Int) -> Unit,
  onDismiss: (() -> Unit)?,
  modifier: Modifier,
) {
  var picked by remember { mutableIntStateOf(-1) }
  CardLayout(
    header = header,
    answers = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s2),
      ) {
        choices.forEachIndexed { index, choice -> ChoiceRow(choice, picked = index == picked) { picked = index } }
      }
    },
    buttons = { SubmitButtons(canSubmit = picked >= 0, onSubmit = { onSubmit(picked) }, onDismiss = onDismiss) },
    answersScroll = true,
    modifier = modifier,
  )
}

/** Paseo's radio row: the picked one is highlighted, with an accent dot in its circle. */
@Composable
private fun ChoiceRow(label: String, picked: Boolean, onPick: () -> Unit) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(if (picked) colors.surface2 else Color.Transparent)
      .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
      .heightIn(min = 40.dp)
      .padding(horizontal = 10.dp, vertical = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(16.dp)
        .border(1.dp, if (picked) colors.accent else colors.foregroundExtraMuted, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      if (picked) Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(colors.accent))
    }
    Text(
      text = label,
      color = if (picked) colors.foreground else colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
  }
}

/**
 * A filled field with the cursor after the `prefill` and the keyboard up: one line for `input`, which [CardLayout]
 * always gives its full height, and several for `editor`, scrolling when [CardLayout] gives it less than its height.
 * Submit is off while the text is blank. The keyboard's action key does not submit, only the button.
 */
@Composable
private fun TextCard(
  request: ExtensionUiRequest,
  header: @Composable () -> Unit,
  onAnswer: (UiAnswer) -> Unit,
  modifier: Modifier,
) {
  val colors = pipkinColors
  val multiline = request.method == "editor"
  val state = rememberTextFieldState(request.prefill.orEmpty())
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  val style = LocalTextStyle.current.merge(TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.base))
  val shape = RoundedCornerShape(PipkinTokens.Radius.xl)
  CardLayout(
    header = header,
    answers = {
      BasicTextField(
        state = state,
        modifier = Modifier
          .fillMaxWidth()
          .then(if (multiline) Modifier.heightIn(min = 120.dp) else Modifier)
          .clip(shape)
          .background(colors.surface2)
          .border(1.dp, colors.borderAccent, shape)
          .padding(PipkinTokens.Spacing.s12)
          .focusRequester(focusRequester),
        textStyle = style,
        lineLimits = if (multiline) TextFieldLineLimits.MultiLine() else TextFieldLineLimits.SingleLine,
        // An `input` often asks for a name or a path, which a capital first letter would change.
        keyboardOptions = KeyboardOptions(
          capitalization = if (multiline) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        cursorBrush = SolidColor(colors.accent),
        decorator = TextFieldDecorator { inner ->
          Box {
            val placeholder = request.placeholder
            if (state.text.isEmpty() && placeholder != null) {
              Text(text = placeholder, style = style.copy(color = colors.foregroundMuted))
            }
            inner()
          }
        },
      )
    },
    buttons = {
      SubmitButtons(
        canSubmit = state.text.isNotBlank(),
        onSubmit = { onAnswer(UiAnswer.Value(state.text.toString())) },
        onDismiss = { onAnswer(UiAnswer.Cancelled) },
      )
    },
    answersScroll = multiline,
    modifier = modifier,
  )
}

/** Dismiss, when [onDismiss] is given, above the accent Submit, both full width as in Paseo's question card. */
@Composable
private fun SubmitButtons(canSubmit: Boolean, onSubmit: () -> Unit, onDismiss: (() -> Unit)?) {
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    onDismiss?.let { CardButton(stringResource(R.string.chat_dismiss), R.drawable.ic_lucide_x, onClick = it) }
    CardButton(
      stringResource(R.string.chat_submit),
      R.drawable.ic_lucide_check,
      filled = true,
      enabled = canSubmit,
      onClick = onSubmit,
    )
  }
}

/** Paseo's full-width action button: muted text on the card, or accent-filled when [filled]. */
@Composable
private fun CardButton(
  label: String,
  @DrawableRes icon: Int,
  filled: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  val content = if (filled) colors.accentForeground else colors.foregroundMuted
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .alpha(if (enabled) 1f else DISABLED_ALPHA)
      .clip(shape)
      .background(if (filled) colors.accent else colors.surface1)
      .border(1.dp, if (filled) colors.accent else colors.surface3, shape)
      .clickable(enabled = enabled, onClick = onClick)
      .heightIn(min = 40.dp)
      .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s8),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LucideIcon(icon, contentDescription = null, size = PipkinTokens.IconSize.sm, tint = content)
    Text(text = label, color = content, fontSize = PipkinTokens.FontSize.base)
  }
}

private const val MILLIS_PER_SECOND = 1000L
private const val DISABLED_ALPHA = 0.5f

/** The space between the header, the answers and the buttons. */
private val CARD_GAP = 10.dp
