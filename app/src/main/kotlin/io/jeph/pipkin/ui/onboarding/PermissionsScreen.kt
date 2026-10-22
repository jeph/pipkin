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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.termux.TermuxSetup
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.RoundIconButton
import kotlinx.coroutines.launch

/**
 * The setup steps that prepare Termux and grant Pipkin access, as cards, without the agent install and sign-in.
 * Reached from Settings; steps re-check whenever Pipkin comes back, like onboarding.
 */
@Composable
internal fun PermissionsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val graph = remember(context) { context.appGraph }
  val checks = rememberDeviceChecks()
  val state = rememberOnboardingState(graph, checks.value)
  val onAction = rememberSetupActions(state, onPermissionResult = checks::refresh)
  AutoInstall(graph, state)

  SetupPage(
    header = {
      HeaderBar(
        title = stringResource(R.string.settings_permissions),
        leading = {
          RoundIconButton(
            icon = R.drawable.ic_lucide_chevron_left,
            contentDescription = stringResource(R.string.settings_back),
            onClick = onBack,
          )
        },
      )
    },
  ) {
    val steps = state.visibleSteps - SetupStep.InstallPi - SetupStep.SignIn
    steps.forEachIndexed { index, step -> StepCard(stepSpec(step, state, onAction), number = index + 1) }
  }
}

/**
 * Handles [SetupAction]s; [onPermissionResult] runs after a runtime permission prompt closes. [onLogin] and
 * [onAddProvider] serve the sign-in step, which only onboarding shows.
 */
@Composable
internal fun rememberSetupActions(
  state: OnboardingState,
  onPermissionResult: () -> Unit,
  onLogin: (providerId: String) -> Unit = {},
  onAddProvider: () -> Unit = {},
): (SetupAction) -> Unit {
  val context = LocalContext.current
  val graph = remember(context) { context.appGraph }
  val scope = rememberCoroutineScope()
  val requestPermission = rememberPermissionRequest(state.blockedPermissions, onPermissionResult)
  val literal = state.setupLiteral
  return { action ->
    when (action) {
      SetupAction.OpenFDroid -> context.startSafely { TermuxInstall.openFDroidPage(context) }

      SetupAction.OpenPlay -> context.startSafely { TermuxInstall.openPlayPage(context) }

      SetupAction.UninstallTermux -> context.startSafely { TermuxInstall.requestUninstall(context) }

      SetupAction.AllowNotifications -> requestPermission(Manifest.permission.POST_NOTIFICATIONS)

      SetupAction.AllowBackground -> context.startSafely { requestBackgroundExemption(context) }

      SetupAction.AllowTermuxBackground -> context.startSafely { TermuxInstall.requestBackground(context) }

      SetupAction.AllowTermuxStorage -> context.startSafely {
        if (state.storageInSettings) {
          TermuxInstall.openTermuxFilesAccess(context)
        } else {
          TermuxSetup.requestFDroidStorage(context)
          SetupProgress.markStoragePromptShown()
        }
      }

      SetupAction.AllowRunCommand -> requestPermission(TermuxInstall.RUN_COMMAND_PERMISSION)

      SetupAction.CopyAndOpenTermux -> scope.launch {
        val command = SetupProgress.setupCommand(context, literal)
        context.startSafely { TermuxSetup.copyAndOpen(context, command) }
      }

      SetupAction.InstallPi -> SetupProgress.install(graph)

      is SetupAction.Login -> onLogin(action.providerId)

      SetupAction.AddProvider -> onAddProvider()
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

/**
 * Runs an activity start; a device without a matching app (no browser, Termux gone) gets a toast, not a crash, as does
 * a command Termux refused.
 */
private inline fun Context.startSafely(start: () -> Unit) {
  try {
    start()
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(this, R.string.onboarding_no_activity, Toast.LENGTH_SHORT).show()
  } catch (failure: HostFailure) {
    Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show()
  }
}
