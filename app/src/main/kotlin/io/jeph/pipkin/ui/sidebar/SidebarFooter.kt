package io.jeph.pipkin.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Termux/Pi health (tap opens onboarding when setup needs attention), Add workspace and Settings. */
@Composable
internal fun SidebarFooter(onAddWorkspace: () -> Unit, onOpenSettings: () -> Unit, onOpenOnboarding: () -> Unit) {
  val colors = pipkinColors
  val hostClient = LocalContext.current.appGraph.hostClient
  val hello by hostClient.lastHello.collectAsStateWithLifecycle()
  val failure by hostClient.lastFailure.collectAsStateWithLifecycle()
  val installing by hostClient.installing.collectAsStateWithLifecycle()
  val needsSetup = failure?.needsSetup == true
  val dot = when {
    installing -> colors.dotWarning
    failure != null -> colors.dotDanger
    hello != null -> colors.dotSuccess
    else -> null
  }
  val label = if (needsSetup && !installing) {
    stringResource(R.string.shell_termux_attention)
  } else {
    hello?.piVersion?.let { stringResource(R.string.shell_termux_status, it) }
      ?: stringResource(R.string.shell_termux_status_unknown)
  }
  Column(modifier = Modifier.fillMaxWidth()) {
    HorizontalDivider(thickness = 1.dp, color = colors.border)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .padding(horizontal = PipkinTokens.Spacing.s16),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      Row(
        modifier = Modifier
          .weight(1f)
          .then(if (needsSetup) Modifier.clickable(onClick = onOpenOnboarding) else Modifier)
          .padding(vertical = PipkinTokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
      ) {
        dot?.let { StatusDot(it) }
        Text(
          text = label,
          color = if (needsSetup) colors.foreground else colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton28(
        icon = R.drawable.ic_lucide_folder_plus,
        contentDescription = stringResource(R.string.shell_add_workspace),
        onClick = onAddWorkspace,
      )
      IconButton28(
        icon = R.drawable.ic_lucide_settings,
        contentDescription = stringResource(R.string.shell_settings),
        onClick = onOpenSettings,
      )
    }
  }
}
