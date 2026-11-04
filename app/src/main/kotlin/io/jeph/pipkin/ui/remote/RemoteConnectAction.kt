package io.jeph.pipkin.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.RemoteFailure
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.sidebar.rememberFolderNames
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * Under a failure in the workspace [cwd] whose connection failure [code] says connecting to its remote host would fix
 * it (signing in, or accepting the host's key): Connect, then [onConnected] to load again; while connecting, that it
 * is. Nothing for a local workspace, a removed host or another failure. Any connection failure is noted on the host,
 * so the sidebar and the chat banner show it as disconnected too.
 */
@Composable
internal fun RemoteConnectAction(cwd: String, code: String?, onConnected: () -> Unit) {
  val remote = rememberFolderNames().remote(cwd)
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  LaunchedEffect(remote?.hostId, code) {
    if (remote != null && code != null) remoteHosts.noteFailure(remote.hostId, code)
  }
  val host = remote?.host
  if (host == null || code == null || !RemoteFailure.needsConnect(code)) return
  val connector = rememberRemoteConnector()
  if (connector.connecting) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    ) {
      SimmeringPot()
      Text(
        text = stringResource(R.string.remote_connecting, host.name),
        color = pipkinColors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
      )
    }
  } else {
    PillButton(
      label = stringResource(R.string.remote_connect),
      onClick = { connector.connect(host.id, host.name, onConnected) },
    )
  }
  RemoteConnectDialogs(connector)
}
