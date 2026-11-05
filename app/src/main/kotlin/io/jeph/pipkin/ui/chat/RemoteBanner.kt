package io.jeph.pipkin.ui.chat

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.ui.components.DialogConfirm
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PipkinDialog
import io.jeph.pipkin.ui.remote.RemoteConnectDialogs
import io.jeph.pipkin.ui.remote.rememberRemoteConnector
import io.jeph.pipkin.ui.sidebar.rememberFolderNames
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The warning floating over a remote chat's stream while its host is lost: why, in the host's words, and Connect when
 * only the user can sign in or check the host's key. The chat checks the host while it's on screen
 * ([RemoteChecks]), so the warning comes up within seconds of the host going, before anyone sends a message, and goes
 * once it answers again; until then the composer won't send. A connect that works takes it down at once. While Pipkin
 * signs a host in again with its saved password, the warning says it's reconnecting; when the server refused the
 * password, or the host's key changed so Pipkin didn't send it, it says so and offers to fix that.
 */
@Composable
internal fun RemoteBanner(process: AgentProcess, modifier: Modifier = Modifier) {
  RemoteChecks(process)
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  val outage by process.remoteOutages.state.collectAsStateWithLifecycle()
  val reconnecting by remoteHosts.reconnecting.collectAsStateWithLifecycle()
  val savedPasswords by remoteHosts.savedPasswords.collectAsStateWithLifecycle()
  val keyChanged by remoteHosts.hostKeyChanged.collectAsStateWithLifecycle()
  // Ahead of the banner, so a connect's failure stays up when a check takes the banner down meanwhile.
  val connector = rememberRemoteConnector()
  RemoteConnectDialogs(connector)
  var reviewing by remember { mutableStateOf(false) }
  // From the hosts as they are now: the outage may have come before the host list knew the chat's folder.
  val host = rememberFolderNames().remote(process.cwd)?.host
  if (reviewing && host != null) {
    KeyChangedDialog(host, onDismiss = { reviewing = false }) {
      reviewing = false
      connector.connect(host.id, host.name, onConnected = process.remoteOutages::clear)
    }
  }
  val signingIn = connector.connecting || host?.id in reconnecting
  if (outage == null && !signingIn) return
  val name = host?.name ?: stringResource(R.string.remote_host_removed)
  val failure = outage?.failure
  val connect = { id: String -> connector.connect(id, name, onConnected = process.remoteOutages::clear) }
  val shown = when {
    signingIn -> Shown(R.drawable.ic_lucide_wifi_off, stringResource(R.string.remote_banner_reconnecting, name))

    host != null && savedPasswords[host.id] == true ->
      Shown(R.drawable.ic_lucide_key_round, stringResource(R.string.remote_banner_password_rejected, name)) {
        BannerTextButton(stringResource(R.string.remote_enter_password)) { connect(host.id) }
      }

    host != null && host.id in keyChanged && host.id in savedPasswords ->
      Shown(R.drawable.ic_lucide_triangle_alert, stringResource(R.string.remote_banner_key_changed_password, name)) {
        BannerTextButton(stringResource(R.string.remote_review)) { reviewing = true }
      }

    failure != null -> Shown(R.drawable.ic_lucide_wifi_off, failure.text.localized()) {
      if (host != null &&
        failure.needsConnect
      ) {
        BannerTextButton(stringResource(R.string.remote_connect)) { connect(host.id) }
      }
    }

    else -> Shown(R.drawable.ic_lucide_wifi_off, stringResource(R.string.remote_banner_disconnected, name))
  }
  BannerPill(shown, modifier)
}

/** What the banner shows: an [icon], the [text] and an optional [action] button at its end. */
private class Shown(@DrawableRes val icon: Int, val text: String, val action: (@Composable () -> Unit)? = null)

@Composable
private fun BannerPill(shown: Shown, modifier: Modifier) {
  val colors = pipkinColors
  // A button brings its own padding.
  val end = if (shown.action == null) PipkinTokens.Spacing.s16 else PipkinTokens.Spacing.s4
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
    LucideIcon(icon = shown.icon, contentDescription = null, tint = colors.dotWarning)
    Text(
      text = shown.text,
      // Not filling: a short warning makes a short pill.
      modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
      color = colors.dotWarning,
      fontSize = PipkinTokens.FontSize.base,
      fontWeight = FontWeight.SemiBold,
      lineHeight = 19.sp,
    )
    shown.action?.invoke()
  }
}

/**
 * Why Pipkin didn't send [host]'s saved password: its key isn't the one the user accepted. Forget host key, for a key
 * the user knows changed, forgets it and runs [onForgotten], which connects and asks about the new key.
 */
@Composable
private fun KeyChangedDialog(host: RemoteHost, onDismiss: () -> Unit, onForgotten: () -> Unit) {
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  val scope = rememberCoroutineScope()
  PipkinDialog(
    title = stringResource(R.string.remote_key_changed_title, host.name),
    text = stringResource(R.string.remote_key_changed_warning, host.name),
    icon = R.drawable.ic_lucide_triangle_alert,
    confirm = DialogConfirm(
      label = stringResource(R.string.remote_forget_key),
      onClick = {
        scope.launch {
          try {
            remoteHosts.forgetHostKey(host.id)
          } catch (_: HostRejected) {
            // The connect fails on the old key again and says why.
          } catch (_: IOException) {
            // Likewise.
          }
          onForgotten()
        }
      },
      destructive = true,
    ),
    dismissLabel = stringResource(R.string.remote_cancel),
    onDismiss = onDismiss,
  )
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
