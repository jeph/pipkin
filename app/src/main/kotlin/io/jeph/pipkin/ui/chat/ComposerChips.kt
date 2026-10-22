package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.json.JSONObject

/** The current model's name, ellipsized when [modifier] gives it less room; opens the model picker. */
@Composable
internal fun ModelChip(model: JSONObject?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  ToolbarChip(onClick, onClickLabel = stringResource(R.string.chat_model), modifier) {
    ChipText(
      text = modelLabel(model) ?: stringResource(R.string.chat_no_model_chip),
      modifier = Modifier.widthIn(max = 180.dp),
    )
  }
}

/** The reasoning effort Pi reports for the chat; opens the reasoning picker. */
@Composable
internal fun ThinkingChip(thinkingLevel: String?, onClick: () -> Unit) {
  ToolbarChip(onClick, onClickLabel = stringResource(R.string.chat_reasoning)) {
    LucideIcon(
      icon = R.drawable.ic_lucide_brain,
      contentDescription = null,
      size = PipkinTokens.IconSize.sm,
      tint = pipkinColors.foregroundMuted,
    )
    ChipText(thinkingLevel?.let { thinkingLevelLabel(it) }.orEmpty())
  }
}

@Composable
private fun ChipText(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier,
    color = pipkinColors.foreground,
    fontSize = 12.5.sp,
    fontWeight = FontWeight.Medium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** A 30dp pill on surface2 in the composer toolbar. */
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
        .heightIn(min = 30.dp)
        .clip(CircleShape)
        .background(pipkinColors.surface2)
        .clickable(onClickLabel = onClickLabel, onClick = onClick)
        .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    content = content,
  )
}
