package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.jeph.pipkin.termux.TermuxUtils
import io.jeph.pipkin.termux.TermuxInstallationStatus

@Composable
internal fun TermuxSettings() {
    val context = LocalContext.current
    var installationStatus by remember(context) { mutableStateOf(TermuxInstallationStatus.NOT_INSTALLED) }
    var launchError by remember(context) { mutableStateOf<Int?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installationStatus = TermuxUtils.getTermuxInstallationStatus(context)
        launchError = null
    }

    val supported = installationStatus == TermuxInstallationStatus.SUPPORTED
    val installed = installationStatus != TermuxInstallationStatus.NOT_INSTALLED
    val statusLabel = when (installationStatus) {
        TermuxInstallationStatus.NOT_INSTALLED -> R.string.termux_not_installed
        TermuxInstallationStatus.SUPPORTED -> R.string.termux_installed
        TermuxInstallationStatus.UNSUPPORTED -> R.string.termux_unsupported
    }
    val description = when (installationStatus) {
        TermuxInstallationStatus.NOT_INSTALLED -> R.string.termux_install_description
        TermuxInstallationStatus.SUPPORTED -> R.string.termux_installed_description
        TermuxInstallationStatus.UNSUPPORTED -> R.string.termux_unsupported_description
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
            Text(
                text = stringResource(statusLabel),
                style = MaterialTheme.typography.labelLarge,
                color = if (installed && !supported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (installed && !supported) {
                Text(
                    text = stringResource(R.string.termux_switch_source_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = {
                launchError = null
                installationStatus = TermuxUtils.getTermuxInstallationStatus(context)
                val openInstalled = installationStatus == TermuxInstallationStatus.SUPPORTED
                val opened = if (openInstalled) {
                    TermuxUtils.openTermux(context)
                } else {
                    TermuxUtils.openTermuxInstallationPage(context)
                }
                if (!opened) {
                    launchError = if (openInstalled) R.string.termux_open_error else R.string.termux_install_error
                }
            }) {
                Text(stringResource(if (supported) R.string.termux_open else R.string.termux_install))
            }
            if (installed && !supported) {
                TextButton(onClick = {
                    launchError = null
                    if (!TermuxUtils.openTermux(context)) {
                        launchError = R.string.termux_open_error
                    }
                }) {
                    Text(stringResource(R.string.termux_open))
                }
            }
            launchError?.let { message ->
                Text(
                    text = stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
