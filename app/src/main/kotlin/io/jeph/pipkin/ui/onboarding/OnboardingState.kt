package io.jeph.pipkin.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.host.HostHello
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.termux.TermuxSetup

internal enum class StepStatus { Pending, Running, Done, Failed }

/** What Android reports about Termux and Pipkin's permissions; re-read on every resume. */
internal data class DeviceChecks(
  val termux: TermuxInstall.Status,
  val termuxVersion: String?,
  val runCommandGranted: Boolean,
  /** Null below API 33, where notifications need no runtime permission and the step is hidden. */
  val notificationsGranted: Boolean?,
) {
  companion object {
    fun read(context: Context) = DeviceChecks(
      termux = TermuxInstall.status(context),
      termuxVersion = TermuxInstall.version(context),
      runCommandGranted = TermuxInstall.hasRunCommandPermission(context),
      notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      } else {
        null
      },
    )
  }
}

/** Everything the onboarding page renders, derived into one status per step. */
internal data class OnboardingState(
  val checks: DeviceChecks,
  val hello: HostHello?,
  val expectedPiVersion: String,
  val setupComplete: Boolean,
  val termuxCommandSent: Boolean,
  val blockedPermissions: Set<String>,
  val installing: Boolean,
  val progress: List<String>,
  val error: SetupError?,
  val authStatus: AuthStatus?,
) {
  val piReady: Boolean get() = hello?.piVersion == expectedPiVersion

  /** The host can be launched at all: a supported Termux and the RUN_COMMAND grant. */
  val canLaunchHost: Boolean
    get() = checks.termux == TermuxInstall.Status.Supported && checks.runCommandGranted

  /**
   * Install Pi on resume once the user has run the Termux command in this process. A host that
   * already connected, or a finished setup, proves Termux was prepared earlier.
   */
  val shouldAutoInstall: Boolean
    get() = canLaunchHost && !piReady && !installing && (termuxCommandSent || setupComplete || hello != null)

  /** The setup command, or the narrower fix a failure asked for (`termux-setup-storage`, `yes | pkg upgrade -y`). */
  val termuxCommand: String
    get() = errorFor(SetupStep.TermuxSetup)?.command ?: TermuxSetup.COMMAND

  fun errorFor(step: SetupStep): SetupError? = error?.takeIf { it.step == step }

  fun status(step: SetupStep): StepStatus = when (step) {
    SetupStep.Termux -> termuxStatus()
    SetupStep.Notifications -> if (checks.notificationsGranted == false) StepStatus.Pending else StepStatus.Done
    SetupStep.RunCommand -> runCommandStatus()
    SetupStep.TermuxSetup -> termuxSetupStatus()
    SetupStep.InstallPi -> installPiStatus()
    SetupStep.SignIn -> if (authStatus?.anyConfigured == true) StepStatus.Done else StepStatus.Pending
  }

  private fun termuxStatus(): StepStatus = when (checks.termux) {
    TermuxInstall.Status.Supported -> StepStatus.Done
    TermuxInstall.Status.NotInstalled -> StepStatus.Pending
    else -> StepStatus.Failed
  }

  private fun runCommandStatus(): StepStatus = when {
    checks.runCommandGranted -> StepStatus.Done
    errorFor(SetupStep.RunCommand) != null -> StepStatus.Failed
    else -> StepStatus.Pending
  }

  private fun termuxSetupStatus(): StepStatus = when {
    errorFor(SetupStep.TermuxSetup) != null -> StepStatus.Failed
    hello != null -> StepStatus.Done
    else -> StepStatus.Pending
  }

  private fun installPiStatus(): StepStatus = when {
    installing -> StepStatus.Running
    piReady -> StepStatus.Done
    errorFor(SetupStep.InstallPi) != null -> StepStatus.Failed
    else -> StepStatus.Pending
  }
}
