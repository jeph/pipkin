package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.OpenSshInstall
import io.jeph.pipkin.data.RemoteHosts
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * Settings' row into the remote hosts, counting them. Like the providers row, it shows what the last refresh found
 * and asks for none itself.
 */
@Composable
internal fun RemoteHostsSection(remoteHosts: RemoteHosts, onOpenRemoteHosts: () -> Unit) {
  val remotes by remoteHosts.state.collectAsStateWithLifecycle()
  val count = remotes.hosts.size
  SettingsSection(title = stringResource(R.string.remote_settings_section)) {
    SettingsRow(
      title = stringResource(R.string.remote_hosts_title),
      subtitle = if (count == 0) {
        stringResource(R.string.remote_hosts_none)
      } else {
        pluralStringResource(R.plurals.remote_hosts_count, count, count)
      },
      onClick = onOpenRemoteHosts,
      leading = { ServerIcon() },
    ) {
      Chevron()
    }
  }
}

/** The hosts the user added, each with its address and whether it is connected, then Add host. */
@Composable
internal fun RemoteHostsScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpenHost: (id: String) -> Unit) {
  val remoteHosts = LocalContext.current.appGraph.remoteHosts
  val remotes by remoteHosts.state.collectAsStateWithLifecycle()
  val connected by remoteHosts.connected.collectAsStateWithLifecycle()
  val refreshError by remoteHosts.refreshError.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { remoteHosts.refresh() }
  SettingsPage(title = stringResource(R.string.remote_hosts_title), onBack = onBack) {
    if (remotes.hosts.isEmpty()) {
      SettingsCaption(text = stringResource(R.string.remote_hosts_empty))
    } else {
      SettingsSection(title = null) {
        remotes.hosts.forEachIndexed { index, host ->
          if (index > 0) SettingsDivider()
          val status = stringResource(
            if (connected[host.id] == true) R.string.remote_connected else R.string.remote_not_connected,
          )
          SettingsRow(
            title = host.name,
            subtitle = stringResource(R.string.remote_host_subtitle, host.displayAddress, status),
            onClick = { onOpenHost(host.id) },
            leading = { ServerIcon() },
          ) { Chevron() }
        }
      }
    }
    DashedActionRow(
      label = stringResource(R.string.remote_add_host),
      icon = R.drawable.ic_lucide_plus,
      onClick = onAdd,
    )
    refreshError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
  }
}

/**
 * What Add host shows until Termux has OpenSSH: waiting for this run's first host list, which says whether it is
 * installed, then the offer to install it with `pkg`'s progress, or why the install failed with Try again. Returns
 * whether OpenSSH is known to be installed, so the caller shows the editor in its place.
 */
@Composable
internal fun OpenSshGate(remoteHosts: RemoteHosts): Boolean {
  val remotes by remoteHosts.state.collectAsStateWithLifecycle()
  val loaded by remoteHosts.loaded.collectAsStateWithLifecycle()
  val refreshError by remoteHosts.refreshError.collectAsStateWithLifecycle()
  val install by remoteHosts.install.collectAsStateWithLifecycle()
  val error = refreshError
  when {
    // The saved list may predate OpenSSH being removed or installed, so only this run's answer decides.
    loaded && remotes.sshInstalled -> return true

    !loaded && error == null ->
      Box(modifier = Modifier.fillMaxWidth().padding(top = PipkinTokens.Spacing.s48), Alignment.Center) {
        SimmeringPot(size = PipkinTokens.IconSize.lg)
      }

    !loaded && error != null -> {
      ErrorPanel(title = stringResource(R.string.remote_hosts_failed), message = error)
      SmallButton(label = stringResource(R.string.remote_try_again), onClick = remoteHosts::refresh)
    }

    else -> InstallOpenSsh(install, onInstall = remoteHosts::installOpenSsh)
  }
  return false
}

@Composable
private fun InstallOpenSsh(install: OpenSshInstall, onInstall: () -> Unit) {
  SettingsSection(title = null) {
    SettingsRow(
      title = stringResource(R.string.remote_install_title),
      subtitle = stringResource(R.string.remote_install_text),
      leading = { ServerIcon() },
    )
    if (install is OpenSshInstall.Running) {
      SettingsProgressLine(text = install.line.ifEmpty { stringResource(R.string.remote_installing) })
    }
  }
  if (install is OpenSshInstall.Failed) ErrorPanel(title = null, message = install.error)
  SettingsSaveButton(
    label = stringResource(
      if (install is OpenSshInstall.Failed) R.string.remote_try_again else R.string.remote_install_action,
    ),
    saving = install is OpenSshInstall.Running,
    enabled = install !is OpenSshInstall.Running,
    onClick = onInstall,
  )
}

@Composable
internal fun ServerIcon() {
  LucideIcon(icon = R.drawable.ic_lucide_server, contentDescription = null, tint = pipkinColors.foregroundMuted)
}
