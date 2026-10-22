package io.jeph.pipkin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.sidebar.pathTextStyle
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** The size of [HeaderBar]'s buttons. */
val HeaderButtonSize = 36.dp

/** A [HeaderBar] button; a [filled] one sits on a raised disc, as the explorer's toggle does. */
@Composable
fun HeaderButton(
  @DrawableRes icon: Int,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  filled: Boolean = false,
  tint: Color = pipkinColors.foreground,
) {
  Box(
    modifier = modifier.roundButton(
      size = HeaderButtonSize,
      background = if (filled) pipkinColors.surface2 else Color.Transparent,
      enabled = enabled,
      onClick = onClick,
    ),
    contentAlignment = Alignment.Center,
  ) {
    LucideIcon(icon = icon, contentDescription = contentDescription, size = HEADER_ICON_SIZE, tint = tint)
  }
}

private val HEADER_ICON_SIZE = 18.dp

/** The height of [HeaderBar] below the status bar, which overlays such as Termux's waking pill stay under. */
val HeaderBarHeight = 52.dp

/**
 * The header of a screen: [leading] and [trailing] buttons around a bold [title] over the [folder] it's in, or over
 * the [status] while there is one, which pans when it doesn't fit.
 */
@Composable
fun HeaderBar(
  title: String,
  modifier: Modifier = Modifier,
  folder: String? = null,
  status: AnnotatedString? = null,
  leading: @Composable RowScope.() -> Unit = {},
  trailing: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(pipkinColors.surface0)
        .windowInsetsPadding(WindowInsets.statusBars)
        .heightIn(min = HeaderBarHeight)
        .padding(horizontal = PipkinTokens.Spacing.s12, vertical = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    leading()
    Column(modifier = Modifier.weight(1f).padding(horizontal = PipkinTokens.Spacing.s4)) {
      Text(
        text = title,
        color = pipkinColors.foreground,
        fontSize = PipkinTokens.FontSize.title,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = TITLE_TRACKING,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (status != null) {
        PanningText(status)
      } else if (folder != null) {
        HeaderFolder(folder)
      }
    }
    trailing()
  }
}

/** The header's folder line; a long path keeps its end, the folder that tells workspaces apart. */
@Composable
private fun HeaderFolder(folder: String) {
  val colors = pipkinColors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    LucideIcon(
      icon = R.drawable.ic_lucide_folder,
      contentDescription = null,
      size = PipkinTokens.IconSize.xs,
      tint = colors.foregroundMuted,
    )
    Text(
      text = folder,
      style = pathTextStyle,
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      maxLines = 1,
      overflow = TextOverflow.StartEllipsis,
    )
  }
}

private val TITLE_TRACKING = (-0.025).em
