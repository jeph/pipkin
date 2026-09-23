package io.jeph.pipkin.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.PipkinApplication
import io.jeph.pipkin.R
import io.jeph.pipkin.termux.ConnectionPhase
import io.jeph.pipkin.termux.GenerationInfo
import io.jeph.pipkin.termux.RuntimeFailureKind
import io.jeph.pipkin.termux.TermuxCoordinator
import io.jeph.pipkin.termux.TermuxState

private const val EXTERNAL_APPS_COMMAND =
  "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings"
private const val PREREQUISITES_COMMAND = "pkg install nodejs-lts"
private const val MAX_RUNNING = 4

@Composable
internal fun AgentSettings() {
  val context = LocalContext.current
  val coordinator = (context.applicationContext as PipkinApplication).coordinator
  val state by coordinator.state.collectAsStateWithLifecycle()
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.agent_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
      )
      AgentStatus(state)
      AgentFailure(state, coordinator)
      AgentActions(state, coordinator)
      if (state.phase == ConnectionPhase.Connected) {
        HorizontalDivider()
        AgentSessions(state, coordinator)
      }
      Text(text = stringResource(R.string.agent_background_hint), style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun AgentStatus(state: TermuxState) {
  val label = when (state.phase) {
    ConnectionPhase.Unavailable -> stringResource(R.string.agent_status_unavailable)

    ConnectionPhase.NeedsConsent -> stringResource(R.string.agent_status_consent)

    ConnectionPhase.Disconnected -> stringResource(R.string.agent_status_disconnected)

    ConnectionPhase.Launching -> stringResource(R.string.agent_status_launching)

    ConnectionPhase.Connecting -> stringResource(R.string.agent_status_connecting)

    ConnectionPhase.Preparing -> stringResource(R.string.agent_status_preparing)

    ConnectionPhase.Connected -> stringResource(
      R.string.agent_status_connected,
      state.daemon?.optString("piVersion").orEmpty(),
      state.port,
    )
  }
  val color = when (state.phase) {
    ConnectionPhase.Connected -> MaterialTheme.colorScheme.primary
    ConnectionPhase.Unavailable, ConnectionPhase.Disconnected -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
  }
  Text(
    text = label,
    style = MaterialTheme.typography.labelLarge,
    color = color,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
  if (state.phase == ConnectionPhase.Preparing) {
    state.progress?.let {
      Text(text = it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
  }
  if (state.unresolvedReceipts > 0) {
    Text(
      text = pluralStringResource(R.plurals.agent_uncertain, state.unresolvedReceipts, state.unresolvedReceipts),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

@Composable
private fun AgentFailure(state: TermuxState, coordinator: TermuxCoordinator) {
  val failure = state.failure ?: return
  Text(
    text = failure.message.orEmpty(),
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
  when (failure.kind) {
    RuntimeFailureKind.ExternalApps -> CopyableCommand(EXTERNAL_APPS_COMMAND)

    RuntimeFailureKind.Prerequisites -> CopyableCommand(PREREQUISITES_COMMAND)

    RuntimeFailureKind.Endpoint, RuntimeFailureKind.Authentication ->
      TextButton(onClick = coordinator::repair) { Text(stringResource(R.string.agent_repair)) }

    else -> Unit
  }
}

@Composable
private fun CopyableCommand(command: String) {
  val context = LocalContext.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = command,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = { copy(context, command) }) { Text(stringResource(R.string.agent_copy)) }
  }
}

@Composable
private fun AgentActions(state: TermuxState, coordinator: TermuxCoordinator) {
  var consentDialog by remember { mutableStateOf(false) }
  val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    // Denial is non-fatal: the foreground service still runs without a visible notification.
    coordinator.consent()
  }
  when (state.phase) {
    ConnectionPhase.NeedsConsent -> Button(onClick = {
      consentDialog = true
    }) { Text(stringResource(R.string.agent_consent)) }

    ConnectionPhase.Disconnected -> Button(onClick = coordinator::connect) {
      Text(stringResource(R.string.agent_connect))
    }

    ConnectionPhase.Connected -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = coordinator::disconnect) { Text(stringResource(R.string.agent_disconnect)) }
      TextButton(onClick = coordinator::shutdownDaemon) { Text(stringResource(R.string.agent_shutdown)) }
    }

    else -> Unit
  }
  if (consentDialog) {
    AlertDialog(
      onDismissRequest = { consentDialog = false },
      title = { Text(stringResource(R.string.agent_consent_title)) },
      text = { Text(stringResource(R.string.agent_consent_body)) },
      confirmButton = {
        TextButton(onClick = {
          consentDialog = false
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
          } else {
            coordinator.consent()
          }
        }) { Text(stringResource(R.string.agent_consent_accept)) }
      },
      dismissButton = {
        TextButton(onClick = { consentDialog = false }) { Text(stringResource(R.string.agent_consent_cancel)) }
      },
    )
  }
}

@Composable
private fun AgentSessions(state: TermuxState, coordinator: TermuxCoordinator) {
  Text(
    text = stringResource(R.string.agent_sessions),
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.semantics { heading() },
  )
  for (row in state.generations.sortedBy { it.generation }) SessionRow(row, coordinator)
  val running = state.generations.count { it.running }
  Button(onClick = { coordinator.startAgent() }, enabled = running < MAX_RUNNING) {
    Text(stringResource(R.string.agent_session_start))
  }
}

@Composable
private fun SessionRow(row: GenerationInfo, coordinator: TermuxCoordinator) {
  val status = when {
    row.working -> stringResource(R.string.agent_session_working)
    row.running -> stringResource(R.string.agent_session_idle)
    else -> stringResource(R.string.agent_session_exited, row.signal ?: row.code?.toString() ?: "?")
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = stringResource(R.string.agent_session_row, row.workspace, row.pid, status),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (row.running) {
      TextButton(onClick = {
        coordinator.stopAgent(row.generation)
      }) { Text(stringResource(R.string.agent_session_stop)) }
    }
  }
}

private fun copy(context: Context, text: String) {
  context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pipkin", text))
}
