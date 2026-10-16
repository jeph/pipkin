// POST_NOTIFICATIONS is a compile-time string; the notifications step only exists on API 33+.
@file:SuppressLint("InlinedApi")

package io.jeph.pipkin.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.termux.TermuxSetup
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import kotlinx.coroutines.launch

/**
 * The onboarding steps that set up Termux and grant Pipkin access, without the Pi install and sign-in.
 * Reached from Settings; every step is re-checked on resume, like onboarding.
 */
@Composable
internal fun PermissionsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val graph = remember(context) { context.appGraph }
  var checks by remember { mutableStateOf(DeviceChecks.read(context)) }
  val state = rememberOnboardingState(graph, checks)
  val onAction = rememberPermissionActions(state) { checks = DeviceChecks.read(context) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks = DeviceChecks.read(context) }

  SetupPage(
    header = {
      HeaderBar(
        title = stringResource(R.string.settings_permissions),
        leading = {
          IconButton28(
            icon = R.drawable.ic_lucide_chevron_left,
            contentDescription = stringResource(R.string.settings_back),
            onClick = onBack,
          )
        },
      )
    },
  ) {
    PermissionSteps(state, onAction)
  }
}

/**
 * Termux, Pipkin's Android permissions, then the build's own steps (RUN_COMMAND for F-Droid, background running for
 * Play) and the Termux setup command, in setup order.
 */
@Composable
internal fun PermissionSteps(state: OnboardingState, onAction: (PermissionAction) -> Unit) {
  TermuxStep(
    number = state.stepNumber(SetupStep.Termux),
    status = state.status(SetupStep.Termux),
    termux = state.checks.termux,
    version = state.checks.termuxVersion,
    onAction = onAction,
  )
  AndroidSettingsSteps(
    state = state,
    notificationsBlocked = Manifest.permission.POST_NOTIFICATIONS in state.blockedPermissions,
    onAllowNotifications = { onAction(PermissionAction.AllowNotifications) },
    onAllowBackground = { onAction(PermissionAction.AllowBackground) },
  )
  if (SetupStep.TermuxBackground in state.visibleSteps) {
    TermuxBackgroundStep(
      number = state.stepNumber(SetupStep.TermuxBackground),
      status = state.status(SetupStep.TermuxBackground),
      onAllow = { onAction(PermissionAction.AllowTermuxBackground) },
    )
  }
  if (SetupStep.RunCommand in state.visibleSteps) {
    RunCommandStep(
      number = state.stepNumber(SetupStep.RunCommand),
      status = state.status(SetupStep.RunCommand),
      termuxInstalled = state.checks.termux != TermuxInstall.Status.NotInstalled,
      blocked = TermuxInstall.RUN_COMMAND_PERMISSION in state.blockedPermissions,
      error = state.errorFor(SetupStep.RunCommand),
      onAllow = { onAction(PermissionAction.AllowRunCommand) },
    )
  }
  TermuxSetupStep(
    number = state.stepNumber(SetupStep.TermuxSetup),
    status = state.status(SetupStep.TermuxSetup),
    command = state.termuxCommand,
    termux = state.checks.termux,
    termuxChanged = state.checks.termuxChanged,
    error = state.errorFor(SetupStep.TermuxSetup),
    onCopyAndOpen = { onAction(PermissionAction.CopyAndOpenTermux) },
    onCopy = { onAction(PermissionAction.CopyCommand) },
  )
}

/** Handles [PermissionAction]s; [onPermissionResult] runs after a runtime permission prompt closes. */
@Composable
internal fun rememberPermissionActions(
  state: OnboardingState,
  onPermissionResult: () -> Unit,
): (PermissionAction) -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val requestPermission = rememberPermissionRequest(state.blockedPermissions, onPermissionResult)
  return { action ->
    when (action) {
      PermissionAction.OpenFDroid -> context.startSafely { TermuxInstall.openFDroidPage(context) }

      PermissionAction.OpenPlay -> context.startSafely { TermuxInstall.openPlayPage(context) }

      PermissionAction.UninstallTermux -> context.startSafely { TermuxInstall.requestUninstall(context) }

      PermissionAction.AllowNotifications -> requestPermission(Manifest.permission.POST_NOTIFICATIONS)

      PermissionAction.AllowBackground -> context.startSafely { requestBackgroundExemption(context) }

      PermissionAction.AllowTermuxBackground -> context.startSafely { TermuxInstall.openTermuxAppSettings(context) }

      PermissionAction.AllowRunCommand -> requestPermission(TermuxInstall.RUN_COMMAND_PERMISSION)

      PermissionAction.CopyAndOpenTermux -> scope.launch {
        val command = SetupProgress.setupCommand(context, state.errorFor(SetupStep.TermuxSetup)?.command)
        context.startSafely { TermuxSetup.copyAndOpen(context, command) }
      }

      PermissionAction.CopyCommand -> scope.launch {
        TermuxSetup.copy(context, SetupProgress.setupCommand(context, state.errorFor(SetupStep.TermuxSetup)?.command))
      }
    }
  }
}

/**
 * Returns a request for a runtime permission. Once Android stops prompting ("don't ask again"),
 * the same request opens the system screen where the user can still grant it.
 */
@Composable
private fun rememberPermissionRequest(blocked: Set<String>, onResult: () -> Unit): (String) -> Unit {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val currentBlocked by rememberUpdatedState(blocked)
  val currentOnResult by rememberUpdatedState(onResult)
  fun record(permission: String, granted: Boolean) {
    // Without a rationale after a denial, Android will not show the prompt again.
    val permanentlyDenied = !granted && activity?.shouldShowRequestPermissionRationale(permission) == false
    SetupProgress.setPermissionBlocked(permission, permanentlyDenied)
    currentOnResult()
  }
  val runCommand = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    record(TermuxInstall.RUN_COMMAND_PERMISSION, it)
  }
  val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    record(Manifest.permission.POST_NOTIFICATIONS, it)
  }
  return { permission ->
    val isRunCommand = permission == TermuxInstall.RUN_COMMAND_PERMISSION
    when {
      permission !in currentBlocked -> (if (isRunCommand) runCommand else notifications).launch(permission)
      isRunCommand -> context.startSafely { TermuxInstall.openRunCommandPermissionSettings(context) }
      else -> context.startSafely { openNotificationSettings(context) }
    }
  }
}

private fun openNotificationSettings(context: Context) {
  context.startActivity(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
  )
}

/**
 * Android's own exemption prompt rather than the settings list: Pipkin is the client of a long-running agent and is
 * not distributed through Google Play, whose policy restricts this request.
 */
@SuppressLint("BatteryLife")
private fun requestBackgroundExemption(context: Context) {
  context.startActivity(
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
  )
}

/** Runs an activity start; a device without a matching app (no browser, Termux gone) gets a toast, not a crash. */
private inline fun Context.startSafely(start: () -> Unit) {
  try {
    start()
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(this, R.string.onboarding_no_activity, Toast.LENGTH_SHORT).show()
  }
}
