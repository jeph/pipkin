package io.jeph.pipkin.ui.workspace

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.CapsLabel
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** One thing the shown tab can do, as a tile in the session sheet; [onClick] is null while it can't be done. */
internal class TabAction(
  @StringRes val label: Int,
  @DrawableRes val icon: Int,
  val onClick: (() -> Unit)?,
  val destructive: Boolean = false,
)

/**
 * The actions of the tab a pane shows, which its screen sets while it's composed, for the session sheet the pane
 * opens over it. The sheet belongs to the pane rather than the screen, so it stays open when an action replaces the
 * screen; a screen that leaves clears only what it set itself, never what the screen replacing it set.
 */
@Stable
internal class ShownTabActions {
  var current by mutableStateOf<List<TabAction>?>(null)
    private set
  private var owner: Any? = null

  fun set(owner: Any, actions: List<TabAction>) {
    this.owner = owner
    current = actions
  }

  fun clear(owner: Any) {
    if (this.owner !== owner) return
    this.owner = null
    current = null
  }
}

/** Sets [actions] as what the pane's shown tab can do, for as long as the calling screen is composed. */
@Composable
internal fun PublishTabActions(slot: ShownTabActions, actions: List<TabAction>) {
  val owner = remember { Any() }
  SideEffect { slot.set(owner, actions) }
  DisposableEffect(slot) { onDispose { slot.clear(owner) } }
}

/**
 * The session sheet's section for the shown tab: an Actions label over its [actions] as tiles, three to a row; each
 * action runs once [dismiss] has slid the sheet away.
 */
@Composable
internal fun TabActionGrid(
  actions: List<TabAction>,
  dismiss: (then: () -> Unit) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .padding(horizontal = PipkinTokens.Spacing.s16)
      .padding(top = PipkinTokens.Spacing.s12, bottom = PipkinTokens.Spacing.s8),
    verticalArrangement = Arrangement.spacedBy(TILE_GAP),
  ) {
    SectionLabel(
      stringResource(R.string.shell_actions_label),
      modifier = Modifier.padding(horizontal = PipkinTokens.Spacing.s4),
    )
    actions.chunked(TILES_PER_ROW).forEach { row ->
      // Each row is as tall as its tallest tile, so a label that wraps doesn't leave its neighbours short.
      Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        row.forEach { action ->
          ActionTile(action, dismiss, Modifier.weight(1f).fillMaxHeight())
        }
        repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun ActionTile(action: TabAction, dismiss: (then: () -> Unit) -> Unit, modifier: Modifier) {
  val colors = pipkinColors
  val tint = if (action.destructive) colors.destructive else colors.foreground
  val onClick = action.onClick
  Column(
    modifier = modifier
      .heightIn(min = TILE_HEIGHT)
      .clip(TILE_SHAPE)
      .background(colors.surface2)
      .clickable(enabled = onClick != null, role = Role.Button) { onClick?.let(dismiss) }
      .alpha(if (onClick != null) 1f else DISABLED_ALPHA)
      .padding(horizontal = PipkinTokens.Spacing.s6, vertical = PipkinTokens.Spacing.s12),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    LucideIcon(icon = action.icon, contentDescription = null, tint = tint, size = PipkinTokens.IconSize.md)
    Text(
      text = stringResource(action.label),
      color = tint,
      fontSize = 12.5.sp,
      lineHeight = 15.sp,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** A muted label in capitals over a part of the session sheet. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  CapsLabel(text = text, color = pipkinColors.foregroundMuted, modifier = modifier, fontSize = 11.sp)
}

private const val TILES_PER_ROW = 3
private const val DISABLED_ALPHA = 0.4f
private val TILE_GAP = 8.dp
private val TILE_HEIGHT = 68.dp
private val TILE_SHAPE = RoundedCornerShape(14.dp)
