package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.launch

/** Outcome of the last "Reinstall Pi runtime" run on this screen. */
private sealed interface ReinstallOutcome {
  data class Installed(val version: String) : ReinstallOutcome

  data class Failed(val message: String) : ReinstallOutcome
}

/** Termux and Pi versions, the permissions page, and reinstalling the pinned Pi runtime. */
@Composable
internal fun TermuxSection(graph: AppGraph, onOpenPermissions: () -> Unit) {
  val context = LocalContext.current
  val hostClient = graph.hostClient
  val termuxVersion = remember(context) { TermuxInstall.version(context) }
  val hello by hostClient.lastHello.collectAsStateWithLifecycle()
  val installing by hostClient.installing.collectAsStateWithLifecycle()
  var progressLine by remember { mutableStateOf<String?>(null) }
  var outcome by remember { mutableStateOf<ReinstallOutcome?>(null) }
  val failed = stringResource(R.string.provider_failed)
  val status =
    listOfNotNull(
      termuxVersion ?: stringResource(R.string.settings_termux_not_installed),
      hello?.let { current ->
        current.piVersion?.let { stringResource(R.string.settings_pi_version, it) }
          ?: stringResource(R.string.settings_pi_missing)
      },
    ).joinToString(stringResource(R.string.separator_dot))
  val reinstall = {
    progressLine = null
    outcome = null
    // App scope: leaving Settings must not abort a half-finished npm install.
    graph.scope.launch {
      outcome = try {
        val version = graph.keepAlive.acquire("Pi install").use { hostClient.ensure { line -> progressLine = line } }
        ReinstallOutcome.Installed(version)
      } catch (failure: IOException) {
        ReinstallOutcome.Failed(failure.message ?: failed)
      } catch (rejected: HostRejected) {
        ReinstallOutcome.Failed(rejected.error)
      }
    }
  }
  SettingsSection(title = stringResource(R.string.settings_section_termux)) {
    SettingsRow(title = stringResource(R.string.settings_termux)) { SettingsValue(status) }
    SettingsDivider()
    SettingsRow(title = stringResource(R.string.settings_permissions), onClick = onOpenPermissions)
    SettingsDivider()
    ReinstallRow(
      installing = installing,
      expectedVersion = hostClient.expectedPiVersion,
      progressLine = progressLine,
      outcome = outcome,
      onReinstall = { reinstall() },
    )
  }
}

@Composable
private fun ReinstallRow(
  installing: Boolean,
  expectedVersion: String,
  progressLine: String?,
  outcome: ReinstallOutcome?,
  onReinstall: () -> Unit,
) {
  val colors = pipkinColors
  val subtitle =
    when {
      installing -> stringResource(R.string.settings_reinstall_running, expectedVersion)
      outcome is ReinstallOutcome.Installed -> stringResource(R.string.settings_reinstall_done, outcome.version)
      else -> null
    }
  SettingsRow(
    title = stringResource(R.string.settings_reinstall_pi),
    subtitle = subtitle,
    enabled = !installing,
    onClick = onReinstall,
  ) {
    if (installing) SimmeringPot()
  }
  if (installing && progressLine != null) {
    Text(
      text = progressLine,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s12),
      color = colors.foregroundMuted,
      fontFamily = PipkinFonts.mono,
      fontSize = PipkinTokens.FontSize.code,
      lineHeight = PipkinTokens.LineHeight.code,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  if (outcome is ReinstallOutcome.Failed && !installing) {
    SettingsCaption(
      text = outcome.message,
      modifier = Modifier.padding(start = PipkinTokens.Spacing.s12, bottom = PipkinTokens.Spacing.s12),
      color = colors.destructive,
    )
  }
}
