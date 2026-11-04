package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.remote.RemoteConnectDialogs
import io.jeph.pipkin.ui.remote.rememberRemoteConnector
import io.jeph.pipkin.ui.sidebar.rememberFolderNames
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The warning floating over a remote chat's stream while its host is lost: why, in the host's words, and Connect when
 * only the user can sign in or check the host's key. The chat checks the host while it's on screen
 * ([RemoteChecks]), so the warning comes up within seconds of the host going, before anyone sends a message, and goes
 * once it answers again; until then the composer won't send. A connect that works takes it down at once.
 */
@Composable
internal fun RemoteBanner(process: AgentProcess, modifier: Modifier = Modifier) {
  RemoteChecks(process)
  val outage by process.remoteOutages.state.collectAsStateWithLifecycle()
  // Ahead of the banner, so a connect's failure stays up when a check takes the banner down meanwhile.
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
  val connectTo = host?.takeIf { failure?.needsConnect == true && !connector.connecting }
  // The Connect button brings its own padding.
  val end = if (connectTo == null) PipkinTokens.Spacing.s16 else PipkinTokens.Spacing.s4
  Row(
    modifier = modifier
      .widthIn(max = PipkinTokens.contentMaxWidth)
      .shadow(BANNER_ELEVATION, BANNER_SHAPE)
      .clip(BANNER_SHAPE)
      // Opaque, so it covers the stream's text under it rather than showing it through.
      .background(colors.warningSoft.compositeOver(colors.surface1))
      .semantics { liveRegion = LiveRegionMode.Polite }
      .heightIn(min = 44.dp)
      .padding(start = PipkinTokens.Spacing.s12, end = end),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_wifi_off, contentDescription = null, tint = colors.dotWarning)
    Text(
      text = text,
      // Not filling: a short warning makes a short pill.
      modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
      color = colors.dotWarning,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.SemiBold,
      lineHeight = 19.sp,
    )
    if (connectTo != null) {
      BannerTextButton(stringResource(R.string.remote_connect)) {
        connector.connect(connectTo.id, connectTo.name, onConnected = process.remoteOutages::clear)
      }
    }
  }
}

/**
 * While a remote chat's Pi runs and Pipkin is in front, asks its `pipkin-remote` every [CHECK_INTERVAL] whether the
 * host still answers: once at once, then on and on. A tool call alone finds the host lost only once someone sent a
 * message and the agent reached for it.
 */
@Composable
private fun RemoteChecks(process: AgentProcess) {
  val remote = rememberFolderNames().isRemote(process.cwd)
  val state by process.state.collectAsStateWithLifecycle()
  val running = state == AgentProcessState.Ready
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(process, remote, running, lifecycle) {
    if (!remote || !running) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        process.checkRemote()
        delay(CHECK_INTERVAL)
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

/** How often an on-screen remote chat checks its host; with the check's own 5 s, a lost host shows within 15 s. */
private val CHECK_INTERVAL = 10.seconds

/** Rounded to a pill at one line's height, and still a rounded card when the reason wraps. */
private val BANNER_SHAPE = RoundedCornerShape(22.dp)
private val BANNER_ELEVATION = 6.dp
