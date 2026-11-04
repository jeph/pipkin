package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.remote.RemoteConnectDialogs
import io.jeph.pipkin.ui.remote.rememberRemoteConnector
import io.jeph.pipkin.ui.sidebar.rememberFolderNames
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * The warning under a remote chat's header while a tool call last found its host lost: why, in the host's words, and
 * Connect when only the user can sign in or check the host's key. A connect that works takes the banner down at once;
 * the next tool call would say the host is back anyway.
 */
@Composable
internal fun RemoteBanner(process: AgentProcess, modifier: Modifier = Modifier) {
  val outage by process.remoteOutages.state.collectAsStateWithLifecycle()
  // Ahead of the banner, so a connect's failure stays up when a tool call takes the banner down meanwhile.
  val connector = rememberRemoteConnector()
  RemoteConnectDialogs(connector)
  val shown = outage ?: return
  // From the hosts as they are now: the outage may have come before the host list knew the chat's folder.
  val host = rememberFolderNames().remote(process.cwd)?.host
  val name = host?.name ?: stringResource(R.string.remote_host_removed)
  val failure = shown.failure
  val text = when {
    connector.connecting -> stringResource(R.string.remote_banner_reconnecting, name)
    failure != null -> failure.text.localized()
    else -> stringResource(R.string.remote_banner_disconnected, name)
  }
  val colors = pipkinColors
  Row(
    modifier = modifier
      .widthIn(max = PipkinTokens.contentMaxWidth)
      .fillMaxWidth()
      .clip(RoundedCornerShape(PipkinTokens.Radius.lg))
      .background(colors.warningSoft)
      .semantics { liveRegion = LiveRegionMode.Polite }
      .heightIn(min = 44.dp)
      .padding(start = PipkinTokens.Spacing.s12, end = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_wifi_off, contentDescription = null, tint = colors.dotWarning)
    Text(
      text = text,
      modifier = Modifier.weight(1f).padding(vertical = 10.dp),
      color = colors.dotWarning,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.SemiBold,
      lineHeight = 19.sp,
    )
    if (host != null && failure?.needsConnect == true && !connector.connecting) {
      BannerTextButton(stringResource(R.string.remote_connect)) {
        connector.connect(host.id, host.name, onConnected = process.remoteOutages::clear)
      }
    }
  }
}

/** Host [hostId] as the app knows it now; null once it was removed. */
@Composable
internal fun remoteHost(hostId: String): RemoteHost? {
  val remotes by LocalContext.current.appGraph.remoteHosts.state.collectAsStateWithLifecycle()
  return remotes.host(hostId)
}

@Composable
private fun BannerTextButton(label: String, onClick: () -> Unit) {
  Text(
    text = label,
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 9.dp),
    color = pipkinColors.accent,
    fontSize = PipkinTokens.FontSize.base,
    fontWeight = FontWeight.Bold,
  )
}
