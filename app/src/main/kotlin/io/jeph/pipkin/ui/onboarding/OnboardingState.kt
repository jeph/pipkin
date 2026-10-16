package io.jeph.pipkin.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.host.HelperPairing
import io.jeph.pipkin.host.HostHello
import io.jeph.pipkin.termux.TermuxIdentity
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.termux.TermuxSetup

internal enum class StepStatus { Pending, Running, Done, Failed }

/** What a tap on a [PermissionSteps] step asks for; handled by [rememberPermissionActions]. */
internal sealed interface PermissionAction {
  data object OpenFDroid : PermissionAction

  data object OpenPlay : PermissionAction

  data object UninstallTermux : PermissionAction

  data object AllowNotifications : PermissionAction

  data object AllowBackground : PermissionAction

  data object AllowTermuxBackground : PermissionAction

  data object AllowRunCommand : PermissionAction

  data object CopyAndOpenTermux : PermissionAction

  data object CopyCommand : PermissionAction
}

/** What Android reports about Termux and Pipkin's permissions; re-read on every resume. */
internal data class DeviceChecks(
  val termux: TermuxInstall.Status,
  val termuxVersion: String?,
  val runCommandGranted: Boolean,
  /** Null below API 33, where notifications need no runtime permission and the step is hidden. */
  val notificationsGranted: Boolean?,
  /** Pipkin is exempt from battery optimization, so Android keeps its connection to the agent while the screen is off. */
  val backgroundAllowed: Boolean,
  /** Termux is exempt from battery optimization; only asked for with the Play build, whose helper runs all the time. */
  val termuxBackgroundAllowed: Boolean,
  /** Pipkin holds a pairing key for a helper in the Play build of Termux. */
  val helperPaired: Boolean,
  /** Termux was reinstalled or swapped since a host last ran, so its setup is gone. */
  val termuxChanged: Boolean,
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
      backgroundAllowed = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
      termuxBackgroundAllowed = TermuxInstall.termuxBackgroundAllowed(context),
      helperPaired = HelperPairing.isPaired(context),
      termuxChanged = TermuxIdentity.changed(context),
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
  /** The Play build's setup command last copied in this process; each copy makes a fresh one. */
  val playCommand: String?,
  val blockedPermissions: Set<String>,
  val installing: Boolean,
  val progress: List<String>,
  val error: SetupError?,
  val authStatus: AuthStatus?,
) {
  val piReady: Boolean get() = hello?.piVersion == expectedPiVersion

  /** The host can be launched at all: F-Droid's Termux with the RUN_COMMAND grant, or Play's with a paired helper. */
  val canLaunchHost: Boolean
    get() = when (checks.termux) {
      TermuxInstall.Status.FDroid -> checks.runCommandGranted
      TermuxInstall.Status.Play -> checks.helperPaired
      else -> false
    }

  /**
   * Install Pi on resume once the user has run the Termux command in this process. A host that
   * already connected, or a finished setup, proves Termux was prepared earlier.
   */
  val shouldAutoInstall: Boolean
    get() = canLaunchHost && !piReady && !installing && (termuxCommandSent || setupComplete || hello != null)

  /**
   * The command the setup step shows and copies: the narrower fix a failure asked for (`termux-setup-storage`,
   * `yes | pkg upgrade -y`), else F-Droid's setup command, or the Play build's last copied one (null before the first
   * copy, since each copy makes a fresh one).
   */
  val termuxCommand: String?
    get() = errorFor(SetupStep.TermuxSetup)?.command
      ?: if (checks.termux.isPlay) playCommand else TermuxSetup.FDROID_COMMAND

  /** The steps this phone and Termux build need, in display order. */
  val visibleSteps: List<SetupStep>
    get() = SetupStep.entries.filter { step ->
      when (step) {
        SetupStep.Notifications -> checks.notificationsGranted != null
        SetupStep.TermuxBackground -> checks.termux.isPlay
        SetupStep.RunCommand -> !checks.termux.isPlay
        else -> true
      }
    }

  fun errorFor(step: SetupStep): SetupError? = error?.takeIf { it.step == step }

  /** The number shown on [step]'s card, counting only [visibleSteps]. */
  fun stepNumber(step: SetupStep): Int = visibleSteps.indexOf(step) + 1

  fun status(step: SetupStep): StepStatus = when (step) {
    SetupStep.Termux -> termuxStatus()
    SetupStep.Notifications -> if (checks.notificationsGranted == false) StepStatus.Pending else StepStatus.Done
    SetupStep.Background -> if (checks.backgroundAllowed) StepStatus.Done else StepStatus.Pending
    SetupStep.TermuxBackground -> if (checks.termuxBackgroundAllowed) StepStatus.Done else StepStatus.Pending
    SetupStep.RunCommand -> runCommandStatus()
    SetupStep.TermuxSetup -> termuxSetupStatus()
    SetupStep.InstallPi -> installPiStatus()
    SetupStep.SignIn -> if (authStatus?.anyConfigured == true) StepStatus.Done else StepStatus.Pending
  }

  private fun termuxStatus(): StepStatus = when (checks.termux) {
    TermuxInstall.Status.FDroid, TermuxInstall.Status.Play -> StepStatus.Done
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

    // A hello from before the reinstall doesn't count: the new Termux hasn't run anything yet.
    checks.termuxChanged -> StepStatus.Pending

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
