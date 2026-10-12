package io.jeph.pipkin.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A setup page: [header] over a scrolling, width-capped column of step cards. */
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

/** A numbered setup step: surface1 card, 1dp border, radius 8, status badge and title over [content]. */
@Composable
internal fun StepCard(
  number: Int,
  title: String,
  status: StepStatus,
  modifier: Modifier = Modifier,
  tag: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.lg)
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(colors.surface1, shape)
        .border(1.dp, colors.border, shape)
        .padding(PipkinTokens.Spacing.s16),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      StatusBadge(number, status)
      Text(
        text = title,
        modifier = Modifier.weight(1f),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.Medium,
      )
      if (tag != null) {
        Text(text = tag, color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
      }
    }
    content()
  }
}

@Composable
private fun StatusBadge(number: Int, status: StepStatus) {
  val colors = pipkinColors
  Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
    when (status) {
      StepStatus.Done -> LucideIcon(
        icon = R.drawable.ic_lucide_check,
        contentDescription = stringResource(R.string.onboarding_status_done),
        tint = colors.accent,
      )

      StepStatus.Running -> SimmeringPot(tint = colors.foreground)

      StepStatus.Failed -> LucideIcon(
        icon = R.drawable.ic_lucide_circle_alert,
        contentDescription = stringResource(R.string.onboarding_status_failed),
        tint = colors.destructive,
      )

      StepStatus.Pending -> Box(
        modifier = Modifier.size(22.dp).border(1.dp, colors.borderAccent, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(text = number.toString(), color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.sm)
      }
    }
  }
}

/** The muted explanation under a step title, or an error in destructive. */
@Composable
internal fun StepText(text: String, modifier: Modifier = Modifier, color: Color = pipkinColors.foregroundMuted) {
  Text(text = text, modifier = modifier, color = color, fontSize = PipkinTokens.FontSize.meta)
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

/** A Termux command in monospace on surface2, with a copy button. */
@Composable
internal fun CommandBox(command: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.md)
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(colors.surface2, shape)
        .border(1.dp, colors.border, shape)
        .padding(start = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s4)
        .padding(vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Text(
      text = command,
      modifier = Modifier.weight(1f).padding(vertical = PipkinTokens.Spacing.s4),
      color = colors.foreground,
      fontFamily = FontFamily.Monospace,
      fontSize = PipkinTokens.FontSize.code,
      lineHeight = PipkinTokens.LineHeight.code,
    )
    IconButton28(
      icon = R.drawable.ic_lucide_copy,
      contentDescription = stringResource(R.string.onboarding_copy_command),
      onClick = onCopy,
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
        fontFamily = FontFamily.Monospace,
        fontSize = PipkinTokens.FontSize.code,
        lineHeight = PipkinTokens.LineHeight.code,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private val ButtonPadding = PaddingValues(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s6)
