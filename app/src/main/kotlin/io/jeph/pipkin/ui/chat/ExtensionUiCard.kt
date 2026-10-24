package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.ExtensionUiRequest
import io.jeph.pipkin.agent.SubagentAsker
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.agent.UiAnswer
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.PillTone
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay

/**
 * A Pi extension dialog (`docs/rpc-extension-ui.md`) as a card above the composer, the way Paseo shows an agent's
 * questions, so the chat and the composer stay usable while it waits. Only Submit answers, after an option is picked
 * or text is typed, so a stray tap never answers; Dismiss answers `cancelled`. [number] of [count] shows when
 * several dialogs came in together, and the seconds left when Pi answers the dialog itself after a timeout. Above the
 * title, [asker] names the subagent that asked, or says one of several did; see [io.jeph.pipkin.agent.askingSubagent].
 */
@Composable
internal fun ExtensionUiCard(
  request: ExtensionUiRequest,
  number: Int,
  count: Int,
  asker: SubagentAsker,
  onAnswer: (UiAnswer) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  // The pill naming who asked, with its gap to the title, measured rather than counted in sp, as Android scales large
  // sizes less. It comes on top of the card's half of the chat, rather than out of the answers.
  val measurer = rememberTextMeasurer(cacheSize = 0)
  val pillStyle = LocalTextStyle.current.merge(PILL_TEXT_STYLE)
  val density = LocalDensity.current
  val askerShown = asker != SubagentAsker.Unknown
  val askerRoom = remember(measurer, pillStyle, density, askerShown) {
    if (!askerShown) return@remember 0
    with(density) {
      maxOf(measurer.measure("", pillStyle).size.height, PILL_MIN_HEIGHT.roundToPx()) +
        PipkinTokens.Spacing.s4.roundToPx()
    }
  }
  Box(
    modifier = modifier.dialogExtraHeight(askerRoom).fillMaxWidth().padding(horizontal = 10.dp),
    Alignment.TopCenter,
  ) {
    key(request.id) {
      val cardModifier = Modifier
        .widthIn(max = PipkinTokens.contentMaxWidth)
        .fillMaxWidth()
        .clip(CARD_SHAPE)
        .background(colors.surface1)
        .padding(
          start = PipkinTokens.Spacing.s12,
          top = PipkinTokens.Spacing.s12,
          end = PipkinTokens.Spacing.s12,
          bottom = 10.dp,
        )
      val header: @Composable () -> Unit = { CardHeader(request, asker, number, count) }
      when (request.method) {
        "select" -> ChoiceCard(
          header = header,
          askerRoom = askerRoom,
          choices = request.options,
          onSubmit = { onAnswer(UiAnswer.Value(request.options[it])) },
          onDismiss = { onAnswer(UiAnswer.Cancelled) },
          modifier = cardModifier,
        )

        // Pi reads a cancelled `confirm` as No, so the card needs no Dismiss.
        "confirm" -> ChoiceCard(
          header = header,
          askerRoom = askerRoom,
          choices = listOf(stringResource(R.string.chat_yes), stringResource(R.string.chat_no)),
          onSubmit = { onAnswer(UiAnswer.Confirmed(it == 0)) },
          onDismiss = null,
          modifier = cardModifier,
        )

        "input", "editor" -> TextCard(request, header, askerRoom, onAnswer, cardModifier)
      }
    }
  }
}

/**
 * Stacks [header], [answers] and [buttons], with the buttons always whole. Pi puts no limit on a title or a message,
 * so when the header and answers together outgrow the card, each first keeps a floor: the header two lines, under the
 * [askerRoom] of the pill naming who asked, and the answers two lines inside a field's padding when [answersScroll], or
 * all of them when they can't scroll, as a one-line field can't. What's left over is split evenly, and a part that
 * needs less than its share gives the rest to the other; a part shorter than its need scrolls inside it. When even the
 * floors don't fit, answers that can't scroll stay whole and the header gets the rest, and otherwise the two floors
 * split the room the same way. The floors make the card's min intrinsic height, so [ChatStack] can make room for them.
 */
@Composable
private fun CardLayout(
  header: @Composable () -> Unit,
  askerRoom: Int,
  answers: @Composable () -> Unit,
  buttons: @Composable () -> Unit,
  answersScroll: Boolean,
  modifier: Modifier,
) {
  // Measured rather than counted in sp: Android scales large sizes less, so 2 × 24sp isn't two lines of 24sp text.
  val measurer = rememberTextMeasurer(cacheSize = 0)
  val style = LocalTextStyle.current.merge(TITLE_STYLE)
  val twoLines = remember(measurer, style) { measurer.measure("\n", style).size.height }
  // The pill above the title would otherwise take most of the header's floor, leaving the question a line or less.
  val policy = remember(twoLines, askerRoom, answersScroll) {
    CardMeasurePolicy(twoLines, twoLines + askerRoom, answersScroll)
  }
  Layout(contents = listOf(header, answers, buttons), modifier = modifier, measurePolicy = policy)
}

/**
 * [twoLines] is the height of two lines of the card's title, the floor of scrolling answers; [headerLines] is the
 * header's floor, those two lines and whatever sits above them.
 */
private class CardMeasurePolicy(
  private val twoLines: Int,
  private val headerLines: Int,
  private val answersScroll: Boolean,
) : MultiContentMeasurePolicy {
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

  private fun headerFloor(need: Int) = minOf(need, headerLines)

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
 * Who asked, then the title and a `confirm`'s message, scrolling together when [CardLayout] gives them less than their
 * height, as Pi's terminal shows them as one text above the options; beside them, the seconds left and the counter.
 */
@Composable
private fun CardHeader(request: ExtensionUiRequest, asker: SubagentAsker, number: Int, count: Int) {
  val colors = pipkinColors
  val title = request.title ?: stringResource(
    when (request.method) {
      "select" -> R.string.chat_choose
      "confirm" -> R.string.chat_confirm_title
      else -> R.string.chat_input_title
    },
  )
  val named = (asker as? SubagentAsker.One)?.run?.names?.joinToString(stringResource(R.string.separator_dot))
    ?.ifEmpty { null }
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    Column(
      modifier = Modifier.weight(
        1f,
      ).verticalScroll(rememberScrollState()).padding(horizontal = PipkinTokens.Spacing.s4),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    ) {
      if (asker != SubagentAsker.Unknown) {
        HeaderPill(
          text = named ?: stringResource(R.string.chat_dialog_from_subagents),
          color = colors.accent,
          background = colors.accentSoft,
          icon = R.drawable.ic_lucide_bot,
          description = named?.let { stringResource(R.string.chat_dialog_from_subagent, it) },
        )
      }
      Text(text = title, color = colors.foreground, style = LocalTextStyle.current.merge(TITLE_STYLE))
      request.message?.let { Text(text = it, color = colors.foregroundMuted, fontSize = 13.5.sp, lineHeight = 20.sp) }
    }
    request.expiresAt?.let { SecondsLeft(it) }
    if (count > 1) {
      HeaderPill(
        text = stringResource(R.string.chat_dialog_counter, number, count),
        color = colors.foregroundMuted,
        background = colors.surface2,
      )
    }
  }
}

/** The agent's name and its task's description, as the extension sent them, leaving out either when blank. */
private val SubagentRun.names: List<String>
  get() = listOf(agent, description).filter { it.isNotBlank() }

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
  val colors = pipkinColors
  HeaderPill(
    text = stringResource(R.string.chat_dialog_seconds_left, seconds),
    color = colors.dotWarning,
    background = colors.warningSoft,
  )
}

/**
 * A small bold pill in the card's header, such as who asked, the counter or the seconds left, led by [icon] when
 * given; screen readers read [description] in its place when given.
 */
@Composable
private fun HeaderPill(
  text: String,
  color: Color,
  background: Color,
  @DrawableRes icon: Int? = null,
  description: String? = null,
) {
  Row(
    modifier = Modifier
      .heightIn(min = PILL_MIN_HEIGHT)
      .clip(CircleShape)
      .background(background)
      .padding(horizontal = 9.dp)
      .then(description?.let { Modifier.clearAndSetSemantics { contentDescription = it } } ?: Modifier),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    icon?.let { LucideIcon(icon = it, contentDescription = null, size = PipkinTokens.IconSize.xs, tint = color) }
    Text(
      text = text,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = LocalTextStyle.current.merge(PILL_TEXT_STYLE),
    )
  }
}

/**
 * [choices] as radio rows, scrolling when [CardLayout] gives them less than their height; Submit sends the picked one's
 * index and is off until one is picked.
 */
@Composable
private fun ChoiceCard(
  header: @Composable () -> Unit,
  askerRoom: Int,
  choices: List<String>,
  onSubmit: (Int) -> Unit,
  onDismiss: (() -> Unit)?,
  modifier: Modifier,
) {
  var picked by remember { mutableIntStateOf(-1) }
  CardLayout(
    header = header,
    askerRoom = askerRoom,
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

/** A radio row: the picked one is tinted cobalt, with a cobalt dot in its circle. */
@Composable
private fun ChoiceRow(label: String, picked: Boolean, onPick: () -> Unit) {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ROW_SHAPE)
      .background(if (picked) colors.accentSoft else Color.Transparent)
      .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
      .heightIn(min = 40.dp)
      .padding(horizontal = 10.dp, vertical = PipkinTokens.Spacing.s8),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(18.dp)
        .border(2.dp, if (picked) colors.accent else colors.foregroundExtraMuted, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      if (picked) Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(colors.accent))
    }
    Text(text = label, color = if (picked) colors.foreground else colors.foregroundMuted, fontSize = 14.sp)
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
  askerRoom: Int,
  onAnswer: (UiAnswer) -> Unit,
  modifier: Modifier,
) {
  val colors = pipkinColors
  val multiline = request.method == "editor"
  val state = rememberTextFieldState(request.prefill.orEmpty())
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val style = LocalTextStyle.current.merge(TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.base))
  CardLayout(
    header = header,
    askerRoom = askerRoom,
    answers = {
      BasicTextField(
        state = state,
        modifier = Modifier
          .fillMaxWidth()
          .then(if (multiline) Modifier.heightIn(min = 120.dp) else Modifier)
          .clip(FIELD_SHAPE)
          .background(colors.surface0)
          .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.border, FIELD_SHAPE)
          .padding(PipkinTokens.Spacing.s12)
          .focusRequester(focusRequester),
        interactionSource = interaction,
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

/** Dismiss, when [onDismiss] is given, then Submit, side by side as pills. */
@Composable
private fun SubmitButtons(canSubmit: Boolean, onSubmit: () -> Unit, onDismiss: (() -> Unit)?) {
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    onDismiss?.let {
      PillButton(
        label = stringResource(R.string.chat_dismiss),
        onClick = it,
        modifier = Modifier.weight(1f),
        tone = PillTone.Tonal,
      )
    }
    PillButton(
      label = stringResource(R.string.chat_submit),
      onClick = onSubmit,
      modifier = Modifier.weight(1f),
      enabled = canSubmit,
      icon = R.drawable.ic_lucide_check,
    )
  }
}

private const val MILLIS_PER_SECOND = 1000L

private val CARD_SHAPE = RoundedCornerShape(22.dp)
private val ROW_SHAPE = RoundedCornerShape(12.dp)
private val FIELD_SHAPE = RoundedCornerShape(14.dp)

/** The space between the header, the answers and the buttons. */
private val CARD_GAP = 10.dp

/** The text of the small pills in the card's header. */
private val PILL_TEXT_STYLE = TextStyle(
  fontSize = 11.5.sp,
  fontWeight = FontWeight.Bold,
  fontFeatureSettings = PipkinFonts.TABULAR,
)
private val PILL_MIN_HEIGHT = 22.dp

/** The card's title; the header's floor is two lines of it. */
private val TITLE_STYLE = TextStyle(
  fontSize = 15.5.sp,
  fontWeight = FontWeight.ExtraBold,
  lineHeight = 20.sp,
  letterSpacing = (-0.015).em,
)
