package io.jeph.pipkin.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.termux.TermuxSetup
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.PillTone
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A settings-style page: [header] over a scrolling, width-capped column of step cards. */
@Composable
internal fun SetupPage(header: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    header()
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .navigationBarsPadding()
          .padding(PipkinTokens.Spacing.s16),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
        content = content,
      )
    }
  }
}

/** [spec] as a numbered card: a flat surface1 card with its status badge and title over its content. */
@Composable
internal fun StepCard(spec: StepSpec, number: Int, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(colors.surface1, RoundedCornerShape(PipkinTokens.Radius.xxl))
        .padding(PipkinTokens.Spacing.s16),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      StatusBadge(number, spec.status)
      Text(
        text = spec.title,
        modifier = Modifier.weight(1f),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.Bold,
        lineHeight = CARD_TITLE_LINE_HEIGHT,
      )
      requirementLabel(spec.step.requirement)?.let {
        Text(
          text = it,
          modifier = Modifier
            .background(colors.accentSoft, RoundedCornerShape(BADGE_RADIUS))
            .padding(horizontal = PipkinTokens.Spacing.s8, vertical = PipkinTokens.Spacing.s2),
          color = colors.accent,
          fontSize = BADGE_TEXT_SIZE,
          fontWeight = FontWeight.Bold,
        )
      }
    }
    StepText(spec.body)
    spec.error?.let { StepText(it, color = colors.destructive) }
    spec.waiting?.let { WaitingRow(it) }
    spec.extra?.invoke()
    spec.note?.let { StepText(it) }
    if (spec.buttons.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.padding(top = PipkinTokens.Spacing.s4),
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        spec.buttons.forEachIndexed { index, button ->
          // Once a step is done its buttons only redo it, so none of them is the main action any more.
          if (index == 0 && spec.status != StepStatus.Done) {
            PillButton(label = button.label, onClick = button.onClick, enabled = button.enabled)
          } else {
            PillButton(label = button.label, onClick = button.onClick, tone = PillTone.Tonal, enabled = button.enabled)
          }
        }
      }
    }
  }
}

/** "Recommended"; null for required and optional steps. An optional step says so with its Skip for now button. */
@Composable
internal fun requirementLabel(requirement: Requirement): String? = when (requirement) {
  Requirement.Recommended -> stringResource(R.string.onboarding_recommended)
  Requirement.Required, Requirement.Optional -> null
}

/** The step's number on a disc while pending, the pot while running, a filled check once done, or an alert. */
@Composable
private fun StatusBadge(number: Int, status: StepStatus) {
  val colors = pipkinColors
  val circle = Modifier.size(BADGE_SIZE).clip(CircleShape)
  when (status) {
    StepStatus.Done -> Box(circle.background(colors.accent), contentAlignment = Alignment.Center) {
      LucideIcon(
        icon = R.drawable.ic_lucide_check,
        contentDescription = stringResource(R.string.onboarding_status_done),
        size = PipkinTokens.IconSize.sm,
        tint = colors.accentForeground,
      )
    }

    StepStatus.Running -> Box(circle, contentAlignment = Alignment.Center) { SimmeringPot(tint = colors.foreground) }

    StepStatus.Failed -> Box(circle.background(colors.dangerSoft), contentAlignment = Alignment.Center) {
      LucideIcon(
        icon = R.drawable.ic_lucide_circle_alert,
        contentDescription = stringResource(R.string.onboarding_status_failed),
        size = PipkinTokens.IconSize.sm,
        tint = colors.destructive,
      )
    }

    StepStatus.Pending -> Box(circle.background(colors.surface2), contentAlignment = Alignment.Center) {
      Text(
        text = number.toString(),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        fontWeight = FontWeight.Bold,
        style = LocalTextStyle.current.merge(TextStyle(fontFeatureSettings = PipkinFonts.TABULAR)),
      )
    }
  }
}

/** The muted explanation under a step title, or an error in destructive. */
@Composable
internal fun StepText(text: String, modifier: Modifier = Modifier, color: Color = pipkinColors.foregroundMuted) {
  Text(
    text = text,
    modifier = modifier,
    color = color,
    fontSize = PipkinTokens.FontSize.meta,
    lineHeight = STEP_TEXT_LINE_HEIGHT,
  )
}

/** A simmering pot beside what Pipkin is waiting for. */
@Composable
internal fun WaitingRow(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    SimmeringPot(size = PipkinTokens.IconSize.lg, tint = pipkinColors.foreground)
    Text(text = text, color = pipkinColors.foreground, fontSize = PipkinTokens.FontSize.meta)
  }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val colors = pipkinColors
  Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 36.dp),
    enabled = enabled,
    shape = RoundedCornerShape(PipkinTokens.Radius.md),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = colors.accent,
        contentColor = colors.accentForeground,
        disabledContainerColor = colors.surface2,
        disabledContentColor = colors.foregroundExtraMuted,
      ),
    contentPadding = ButtonPadding,
  ) {
    Text(text = text, fontSize = PipkinTokens.FontSize.base)
  }
}

@Composable
internal fun SecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = pipkinColors
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = 36.dp),
    enabled = enabled,
    shape = RoundedCornerShape(PipkinTokens.Radius.md),
    border = BorderStroke(1.dp, colors.borderAccent),
    colors =
      ButtonDefaults.outlinedButtonColors(
        contentColor = colors.foreground,
        disabledContentColor = colors.foregroundExtraMuted,
      ),
    contentPadding = ButtonPadding,
  ) {
    Text(text = text, fontSize = PipkinTokens.FontSize.base)
  }
}

/**
 * A short Termux command in the code font on a surface2 well, which reads on both the onboarding page and a step
 * card, with a button that copies it. It wraps rather than scrolling sideways, so all of it shows before it's copied.
 */
@Composable
internal fun CommandBox(command: String, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val context = LocalContext.current
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(colors.surface2, RoundedCornerShape(COMMAND_RADIUS))
        .padding(start = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s4)
        .padding(vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Text(
      text = command,
      modifier = Modifier.weight(1f).padding(vertical = PipkinTokens.Spacing.s4),
      color = colors.foreground,
      fontFamily = PipkinFonts.mono,
      fontSize = PipkinTokens.FontSize.code,
      lineHeight = PipkinTokens.LineHeight.code,
    )
    RoundIconButton(
      icon = R.drawable.ic_lucide_copy,
      contentDescription = stringResource(R.string.onboarding_copy_command),
      onClick = { TermuxSetup.copy(context, command) },
      tint = colors.foregroundMuted,
      iconSize = PipkinTokens.IconSize.sm,
    )
  }
}

/** The tail of `npm ci` output, one ellipsized monospace line each. */
@Composable
internal fun ProgressLines(lines: List<String>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    lines.forEach { line ->
      Text(
        text = line,
        color = pipkinColors.foregroundMuted,
        fontFamily = PipkinFonts.mono,
        fontSize = PipkinTokens.FontSize.code,
        lineHeight = PipkinTokens.LineHeight.code,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private val STEP_TEXT_LINE_HEIGHT = 19.sp
private val ButtonPadding = PaddingValues(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s6)
private val CARD_TITLE_LINE_HEIGHT = 20.sp
private val BADGE_SIZE = 24.dp
private val BADGE_RADIUS = 8.dp
private val BADGE_TEXT_SIZE = 11.sp
private val COMMAND_RADIUS = 14.dp
