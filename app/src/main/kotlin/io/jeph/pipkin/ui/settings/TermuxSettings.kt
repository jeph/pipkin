package io.jeph.pipkin.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.jeph.pipkin.R
import io.jeph.pipkin.termux.TermuxInstallationStatus
import io.jeph.pipkin.termux.TermuxUtils

@Composable
internal fun TermuxSettings() {
  val context = LocalContext.current
  var installationStatus by remember(context) { mutableStateOf(TermuxInstallationStatus.NOT_INSTALLED) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    installationStatus = TermuxUtils.getTermuxInstallationStatus(context)
  }
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.termux_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
      )
      TermuxInstallationContent(installationStatus)
      TermuxInstallationActions(installationStatus) { installationStatus = it }
      if (installationStatus == TermuxInstallationStatus.SUPPORTED) {
        HorizontalDivider()
        TermuxPermissionSettings { installationStatus = it }
      }
    }
  }
}

@Composable
private fun TermuxInstallationContent(status: TermuxInstallationStatus) {
  val statusLabel = when (status) {
    TermuxInstallationStatus.NOT_INSTALLED -> R.string.termux_not_installed
    TermuxInstallationStatus.SUPPORTED -> R.string.termux_installed
    TermuxInstallationStatus.UNSUPPORTED -> R.string.termux_unsupported
  }
  val description = when (status) {
    TermuxInstallationStatus.NOT_INSTALLED -> R.string.termux_install_description
    TermuxInstallationStatus.SUPPORTED -> R.string.termux_installed_description
    TermuxInstallationStatus.UNSUPPORTED -> R.string.termux_unsupported_description
  }
  val statusColor = if (status == TermuxInstallationStatus.UNSUPPORTED) {
    MaterialTheme.colorScheme.error
  } else {
    MaterialTheme.colorScheme.primary
  }
  Text(
    text = stringResource(statusLabel),
    style = MaterialTheme.typography.labelLarge,
    color = statusColor,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
  Text(text = stringResource(description), style = MaterialTheme.typography.bodyLarge)
  if (status == TermuxInstallationStatus.UNSUPPORTED) {
    Text(
      text = stringResource(R.string.termux_switch_source_warning),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
private fun TermuxInstallationActions(
  status: TermuxInstallationStatus,
  onStatusChanged: (TermuxInstallationStatus) -> Unit,
) {
  val context = LocalContext.current
  var launchError by remember(context) { mutableStateOf<Int?>(null) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { launchError = null }
  Button(onClick = {
    launchError = null
    val currentStatus = TermuxUtils.getTermuxInstallationStatus(context)
    onStatusChanged(currentStatus)
    val openInstalled = currentStatus == TermuxInstallationStatus.SUPPORTED
    try {
      if (openInstalled) {
        TermuxUtils.openTermux(context)
      } else {
        TermuxUtils.openTermuxInstallationPage(context)
      }
    } catch (_: ActivityNotFoundException) {
      launchError = if (openInstalled) R.string.termux_open_error else R.string.termux_install_error
    } catch (_: SecurityException) {
      launchError = R.string.activity_launch_blocked
    }
  }) {
    val label = if (status == TermuxInstallationStatus.SUPPORTED) R.string.termux_open else R.string.termux_install
    Text(stringResource(label))
  }
  if (status == TermuxInstallationStatus.UNSUPPORTED) {
    TextButton(onClick = {
      launchError = null
      try {
        TermuxUtils.openTermux(context)
      } catch (_: ActivityNotFoundException) {
        launchError = R.string.termux_open_error
      } catch (_: SecurityException) {
        launchError = R.string.activity_launch_blocked
      }
    }) {
      Text(stringResource(R.string.termux_open))
    }
  }
  LaunchError(launchError)
}

@Composable
private fun TermuxPermissionSettings(onStatusChanged: (TermuxInstallationStatus) -> Unit) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  var launchError by remember(context) { mutableStateOf<Int?>(null) }
  var permissionGranted by remember(context) { mutableStateOf(false) }
  var permissionDenied by rememberSaveable { mutableStateOf(false) }
  var permissionNeedsSettings by rememberSaveable { mutableStateOf(false) }
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    permissionGranted = granted
    permissionDenied = !granted
    permissionNeedsSettings = !granted &&
      activity?.shouldShowRequestPermissionRationale(TermuxUtils.RUN_COMMAND_PERMISSION) == false
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    permissionGranted = TermuxUtils.hasRunCommandPermission(context)
    if (permissionGranted) {
      permissionDenied = false
      permissionNeedsSettings = false
    } else if (permissionDenied) {
      permissionNeedsSettings =
        activity?.shouldShowRequestPermissionRationale(TermuxUtils.RUN_COMMAND_PERMISSION) == false
    }
    launchError = null
  }
  TermuxPermissionContent(permissionGranted, permissionDenied)
  if (!permissionGranted) {
    TermuxPermissionActions(
      needsSettings = permissionNeedsSettings,
      onRequest = {
        launchError = null
        val status = TermuxUtils.getTermuxInstallationStatus(context)
        onStatusChanged(status)
        permissionGranted = TermuxUtils.hasRunCommandPermission(context)
        if (status == TermuxInstallationStatus.SUPPORTED && !permissionGranted) {
          if (permissionNeedsSettings) {
            launchError = openRunCommandSettings(context)
          } else {
            permissionLauncher.launch(TermuxUtils.RUN_COMMAND_PERMISSION)
          }
        }
      },
      onOpenSettings = { launchError = openRunCommandSettings(context) },
    )
  }
  LaunchError(launchError)
}

@Composable
private fun TermuxPermissionContent(granted: Boolean, denied: Boolean) {
  val label = if (granted) R.string.termux_run_command_granted else R.string.termux_run_command_required
  val statusColor = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
  Text(
    text = stringResource(R.string.termux_run_command_title),
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.semantics { heading() },
  )
  Text(
    text = stringResource(label),
    style = MaterialTheme.typography.labelLarge,
    color = statusColor,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
  Text(
    text = stringResource(R.string.termux_run_command_description),
    style = MaterialTheme.typography.bodyLarge,
  )
  if (granted) {
    Text(
      text = stringResource(R.string.termux_run_command_scope),
      style = MaterialTheme.typography.bodyMedium,
    )
  } else if (denied) {
    LaunchError(R.string.termux_run_command_denied)
  }
}

@Composable
private fun TermuxPermissionActions(needsSettings: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
  val label = if (needsSettings) R.string.termux_run_command_settings else R.string.termux_run_command_allow
  Button(onClick = onRequest) { Text(stringResource(label)) }
  if (!needsSettings) {
    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.termux_run_command_settings)) }
  }
}

@Composable
private fun LaunchError(message: Int?) {
  message?.let {
    Text(
      text = stringResource(it),
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}

private fun openRunCommandSettings(context: Context): Int? = try {
  TermuxUtils.openRunCommandPermissionSettings(context)
  null
} catch (_: ActivityNotFoundException) {
  R.string.termux_run_command_settings_error
} catch (_: SecurityException) {
  R.string.activity_launch_blocked
}
