package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.json.JSONObject

/** The current model's name, ellipsized when [modifier] gives it less room; opens the model picker. */
@Composable
internal fun ModelChip(model: JSONObject?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  ToolbarChip(onClick, onClickLabel = stringResource(R.string.chat_model), modifier) {
    Text(
      text = modelLabel(model) ?: stringResource(R.string.chat_no_model_chip),
      modifier = Modifier.widthIn(max = 180.dp),
      color = pipkinColors.foreground,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** The reasoning effort Pi reports for the chat; opens the reasoning picker. */
@Composable
internal fun ThinkingChip(thinkingLevel: String?, onClick: () -> Unit) {
  val colors = pipkinColors
  ToolbarChip(onClick, onClickLabel = stringResource(R.string.chat_reasoning)) {
    LucideIcon(
      icon = R.drawable.ic_lucide_brain,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = colors.foregroundMuted,
    )
    Text(
      text = thinkingLevel?.let(::levelLabel).orEmpty(),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
    )
  }
}

/** A 28dp pill in the composer toolbar. */
@Composable
private fun ToolbarChip(
  onClick: () -> Unit,
  onClickLabel: String,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    modifier =
      modifier
        .heightIn(min = 28.dp)
        .clip(RoundedCornerShape(PipkinTokens.Radius.xxl))
        .clickable(onClickLabel = onClickLabel, onClick = onClick)
        .padding(horizontal = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4),
    content = content,
  )
}
