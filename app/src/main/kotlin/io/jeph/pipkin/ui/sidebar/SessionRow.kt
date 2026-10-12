package io.jeph.pipkin.ui.sidebar

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.host.SessionInfo
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** One chat in the sidebar: activity mark, title, age and an actions button (long-press opens the same). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
  session: SessionInfo,
  process: AgentProcess?,
  selected: Boolean,
  onOpen: () -> Unit,
  onActions: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = pipkinColors
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s8)
      .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
      .background(if (selected) colors.surface2 else Color.Transparent)
      .combinedClickable(onClick = onOpen, onLongClick = onActions)
      .heightIn(min = 44.dp)
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    Box(modifier = Modifier.size(PipkinTokens.IconSize.md), contentAlignment = Alignment.Center) {
      process?.let { ActivityMark(it) }
    }
    Column(modifier = Modifier.weight(1f).padding(vertical = PipkinTokens.Spacing.s6)) {
      Text(
        text = session.name?.takeIf { it.isNotBlank() }
          ?: session.firstMessage.takeIf { it.isNotBlank() }
          ?: stringResource(R.string.shell_session_untitled),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = relativeTime(session.modified),
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
      )
    }
    IconButton28(
      icon = R.drawable.ic_lucide_ellipsis,
      contentDescription = stringResource(R.string.shell_session_actions),
      onClick = onActions,
      tint = colors.foregroundMuted,
    )
  }
}

/** The simmering pot while Pi works; a danger dot after Pi exited with a failure; nothing otherwise. */
@Composable
private fun ActivityMark(process: AgentProcess) {
  val busy by process.busy.collectAsStateWithLifecycle()
  val state by process.state.collectAsStateWithLifecycle()
  val crashed = (state as? AgentProcessState.Exited)?.code.let { it != null && it != 0 }
  when {
    busy -> SimmeringPot()
    crashed -> StatusDot(pipkinColors.dotDanger)
  }
}

/** `now`, `5m`, `2h`, `3d`, then a short date. */
@Composable
private fun relativeTime(epochMillis: Long): String {
  val elapsed = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0)
  val minutes = elapsed / DateUtils.MINUTE_IN_MILLIS
  val hours = elapsed / DateUtils.HOUR_IN_MILLIS
  val days = elapsed / DateUtils.DAY_IN_MILLIS
  return when {
    minutes < 1 -> stringResource(R.string.shell_time_now)

    hours < 1 -> stringResource(R.string.shell_time_minutes, minutes)

    days < 1 -> stringResource(R.string.shell_time_hours, hours)

    days < 7 -> stringResource(R.string.shell_time_days, days)

    else -> DateUtils.formatDateTime(
      LocalContext.current,
      epochMillis,
      DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
  }
}
