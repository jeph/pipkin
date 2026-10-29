package io.jeph.pipkin.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.host.HelperPairing
import io.jeph.pipkin.host.HostHello
import io.jeph.pipkin.termux.TermuxIdentity
import io.jeph.pipkin.termux.TermuxInstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class StepStatus { Pending, Running, Done, Failed }

/** What a tap on a setup step asks for; handled by [rememberSetupActions]. */
internal sealed interface SetupAction {
  data object OpenFDroid : SetupAction

  data object OpenPlay : SetupAction

  data object UninstallTermux : SetupAction

  data object AllowNotifications : SetupAction

  data object AllowBackground : SetupAction

  data object AllowTermuxBackground : SetupAction

  data object AllowTermuxStorage : SetupAction

  data object AllowRunCommand : SetupAction

  data object CopyAndOpenTermux : SetupAction

  data object InstallPi : SetupAction

  data class Login(val providerId: String) : SetupAction

  data object AddProvider : SetupAction

  data object OpenLocalModels : SetupAction
}

/** What Android reports about Termux and Pipkin's permissions; re-read whenever Pipkin comes back. */
internal data class DeviceChecks(
  val termux: TermuxInstall.Status,
  val termuxVersion: String?,
  /** Identifies this installation of Termux (`TermuxInstall.installation`); a reinstall gets a new one. */
  val termuxInstallation: String?,
  val runCommandGranted: Boolean,
  /** Null below API 33, where notifications need no runtime permission and the step is hidden. */
  val notificationsGranted: Boolean?,
  /** Pipkin is exempt from battery optimization, so Android keeps its connection to the agent while the screen is off. */
  val backgroundAllowed: Boolean,
  /** Termux is exempt from battery optimization, so Android doesn't stop it, and Pi running in it, mid-task. */
  val termuxBackgroundAllowed: Boolean,
  /** Android can ask for [termuxBackgroundAllowed] in a prompt, rather than the user finding it in Termux's app info. */
  val termuxBackgroundPromptable: Boolean,
  /** Pipkin holds a pairing key for a helper in the Play build of Termux. */
  val helperPaired: Boolean,
  /** Termux was reinstalled or swapped since a host last ran, so its setup is gone. */
  val termuxChanged: Boolean,
  /** A host has run in the installed Termux, so its setup was done, even before this process reached it. */
  val termuxReached: Boolean,
) {
  companion object {
    fun read(context: Context) = DeviceChecks(
      termux = TermuxInstall.status(context),
      termuxVersion = TermuxInstall.version(context),
      termuxInstallation = TermuxInstall.installation(context),
      runCommandGranted = TermuxInstall.hasRunCommandPermission(context),
      notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      } else {
        null
      },
      backgroundAllowed = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
      termuxBackgroundAllowed = TermuxInstall.termuxBackgroundAllowed(context),
      termuxBackgroundPromptable = TermuxInstall.canPromptBackground(context),
      helperPaired = HelperPairing.isPaired(context),
      termuxChanged = TermuxIdentity.changed(context),
      termuxReached = TermuxIdentity.reached(context),
    )
  }
}

/** [DeviceChecks] kept current: [refresh] re-reads them for a few seconds, since some settings report late. */
@Stable
internal class LiveDeviceChecks(initial: DeviceChecks) {
  var value by mutableStateOf(initial)
    internal set
  internal var rechecks by mutableIntStateOf(0)
    private set

  fun refresh() {
    rechecks++
  }
}

/**
 * Re-reads [DeviceChecks] when Pipkin resumes or its window regains focus (a permission dialog or the notification
 * shade closing), then every [RECHECK_MILLIS] for a few seconds, since Android reports some changes late. With [poll]
 * it keeps reading while resumed, for steps finished in another app that may not bring Pipkin back.
 */
@Composable
internal fun rememberDeviceChecks(poll: Boolean = false): LiveDeviceChecks {
  val context = LocalContext.current
  val live = remember { LiveDeviceChecks(DeviceChecks.read(context)) }
  var resumed by remember { mutableStateOf(false) }
  val focused = LocalWindowInfo.current.isWindowFocused
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    resumed = true
    live.refresh()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }
  LaunchedEffect(focused) { if (focused) live.refresh() }
  LaunchedEffect(live.rechecks) {
    repeat(RECHECKS) {
      live.value = withContext(Dispatchers.IO) { DeviceChecks.read(context) }
      delay(RECHECK_MILLIS)
    }
  }
  LaunchedEffect(poll && resumed) {
    if (!(poll && resumed)) return@LaunchedEffect
    while (true) {
      delay(POLL_MILLIS)
      live.value = withContext(Dispatchers.IO) { DeviceChecks.read(context) }
    }
  }
  return live
}

private const val RECHECKS = 10
private const val RECHECK_MILLIS = 300L
private const val POLL_MILLIS = 1_000L

/** Everything the setup screens render, derived into one status per step. */
internal data class OnboardingState(
  val checks: DeviceChecks,
  val hello: HostHello?,
  val expectedPiVersion: String,
  val setupComplete: Boolean,
  val commandCopied: Boolean,
  /** [SetupStep] names (and [WELCOME]) the user moved past without finishing; onboarding doesn't reopen them. */
  val passedPages: Set<String>,
  val blockedPermissions: Set<String>,
  val installing: Boolean,
  val progress: List<String>,
  val error: SetupError?,
  val authStatus: AuthStatus?,
  /** Termux's storage prompt was opened this session ([SetupProgress.storagePromptShown]). */
  val storagePromptShown: Boolean = false,
) {
  /**
   * The storage step sends the user to All files access: Play's Termux has no storage prompt, and F-Droid's may have
   * stopped showing it. Android 10 has no such page, so there F-Droid's prompt stays the only way.
   */
  val storageInSettings: Boolean
    get() = checks.termux.isPlay || (storagePromptShown && TermuxInstall.allFilesAccessAvailable)

  /** A hello counts only from the installed Termux: removing or replacing it took Pi and its setup with it. */
  private val currentHello: HostHello? get() = hello.takeIf { checks.termux.isTrusted && !checks.termuxChanged }

  val piReady: Boolean get() = currentHello?.piVersion == expectedPiVersion

  /** The host can be launched at all: F-Droid's Termux with the RUN_COMMAND grant, or Play's with a paired helper. */
  val canLaunchHost: Boolean
    get() = when (checks.termux) {
      TermuxInstall.Status.FDroid -> checks.runCommandGranted
      TermuxInstall.Status.Play -> checks.helperPaired
      else -> false
    }

  /**
   * A short command that fixes the setup failure, such as `yes | pkg upgrade -y`, offered in place of the setup
   * command. It applies to the installation that failed; after a reinstall, only the full setup helps.
   */
  val setupLiteral: String? get() = errorFor(SetupStep.TermuxSetup)?.command?.takeUnless { checks.termuxChanged }

  /**
   * Install Pi once Termux may be prepared: the setup command was copied for this installation, setup finished earlier,
   * or a host already connected. After a reinstall, only a fresh copy counts. The install's first launch is also what
   * proves the setup command ran.
   */
  val shouldAutoInstall: Boolean
    get() = canLaunchHost && !piReady && !installing &&
      (commandCopied || currentHello != null || (setupComplete && !checks.termuxChanged))

  /** The setup command was copied and Termux hasn't reported back yet. */
  val waitingForTermux: Boolean
    get() = commandCopied && status(SetupStep.TermuxSetup) == StepStatus.Pending

  /**
   * Termux can be asked about its storage access: Android reports nothing about another app's storage, so only a fresh
   * hello from Termux can tell. An install's hello answers it too, so this waits for a running one.
   */
  val canCheckStorage: Boolean
    get() = status(SetupStep.TermuxSetup) == StepStatus.Done && canLaunchHost && !installing

  /** Storage access isn't confirmed by a Termux that [canCheckStorage]. */
  val storageUnconfirmed: Boolean get() = canCheckStorage && status(SetupStep.Storage) != StepStatus.Done

  /** The steps this phone and Termux build need, in order. */
  val visibleSteps: List<SetupStep>
    get() = SetupStep.entries.filter { step ->
      when (step) {
        SetupStep.Notifications -> checks.notificationsGranted != null
        SetupStep.RunCommand -> !checks.termux.isPlay
        else -> true
      }
    }

  /**
   * The failure still standing: one reported by an earlier launch (from the chat, say) is dropped once Android shows
   * its step fixed, so fixing it in settings lets the install start without a tap. RUN_COMMAND's step reads the grant
   * itself and says what to do, so the launch's message about it never shows.
   */
  val currentError: SetupError?
    get() = error?.takeUnless { stale ->
      when (stale.step) {
        SetupStep.Termux -> checks.termux.isTrusted
        SetupStep.RunCommand -> true
        else -> false
      }
    }

  fun errorFor(step: SetupStep): SetupError? = currentError?.takeIf { it.step == step }

  /** The first step after [after] (from the start when null) that still needs the user. */
  fun nextOpenStep(after: SetupStep? = null): SetupStep? = visibleSteps
    .dropWhile { after != null && it.ordinal <= after.ordinal }
    .firstOrNull { status(it) != StepStatus.Done && it.name !in passedPages }

  fun status(step: SetupStep): StepStatus = when (step) {
    SetupStep.Termux -> termuxStatus()
    SetupStep.Notifications -> if (checks.notificationsGranted == false) StepStatus.Pending else StepStatus.Done
    SetupStep.Background -> if (checks.backgroundAllowed) StepStatus.Done else StepStatus.Pending
    SetupStep.TermuxBackground -> if (checks.termuxBackgroundAllowed) StepStatus.Done else StepStatus.Pending
    SetupStep.Storage -> if (currentHello?.sharedStorage == true) StepStatus.Done else StepStatus.Pending
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

  private fun runCommandStatus(): StepStatus = if (checks.runCommandGranted) StepStatus.Done else StepStatus.Pending

  private fun termuxSetupStatus(): StepStatus = when {
    !checks.termux.isTrusted -> StepStatus.Pending

    errorFor(SetupStep.TermuxSetup) != null -> StepStatus.Failed

    // A hello from before the reinstall doesn't count: the new Termux hasn't run anything yet.
    checks.termuxChanged -> StepStatus.Pending

    // Without its key, Pipkin can't reach the helper the setup started.
    checks.termux.isPlay && !checks.helperPaired -> StepStatus.Pending

    currentHello != null || checks.termuxReached -> StepStatus.Done

    // The first launch after the command ran is on its way.
    installing -> StepStatus.Running

    else -> StepStatus.Pending
  }

  private fun installPiStatus(): StepStatus = when {
    installing -> StepStatus.Running
    piReady -> StepStatus.Done
    errorFor(SetupStep.InstallPi) != null -> StepStatus.Failed
    else -> StepStatus.Pending
  }

  companion object {
    /** The welcome page's entry in [passedPages]. */
    const val WELCOME = "welcome"
  }
}

@Composable
internal fun rememberOnboardingState(graph: AppGraph, checks: DeviceChecks): OnboardingState {
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val lastFailure by graph.hostClient.lastFailure.collectAsStateWithLifecycle()
  val hostInstalling by graph.hostClient.installing.collectAsStateWithLifecycle()
  val setupComplete by graph.preferences.setupComplete.collectAsStateWithLifecycle()
  val copiedFor by graph.preferences.setupCommandCopiedFor.collectAsStateWithLifecycle()
  val passedPages by graph.preferences.setupPagesPassed.collectAsStateWithLifecycle()
  val authStatus by graph.accountRepository.status.collectAsStateWithLifecycle()
  val blockedPermissions by SetupProgress.blockedPermissions.collectAsStateWithLifecycle()
  val installing by SetupProgress.installing.collectAsStateWithLifecycle()
  val progress by SetupProgress.progress.collectAsStateWithLifecycle()
  val installError by SetupProgress.error.collectAsStateWithLifecycle()
  val storagePromptShown by SetupProgress.storagePromptShown.collectAsStateWithLifecycle()
  return OnboardingState(
    checks = checks,
    hello = hello,
    expectedPiVersion = graph.hostClient.expectedPiVersion,
    setupComplete = setupComplete,
    commandCopied = copiedFor != null && copiedFor == checks.termuxInstallation,
    passedPages = passedPages,
    blockedPermissions = blockedPermissions,
    installing = installing || hostInstalling,
    progress = progress,
    // A failure fixed in Termux brought the user here from elsewhere (chat, sidebar); show it on its step.
    error = installError ?: lastFailure?.takeIf { it.fixedInTermux }?.toSetupError(),
    authStatus = authStatus,
    storagePromptShown = storagePromptShown,
  )
}

/**
 * Starts the Pi install as soon as its prerequisites are met, and retries a failed one when Pipkin comes back (the
 * user may have fixed the problem in Termux). Refreshes sign-in status once Pi is installed.
 */
@Composable
internal fun AutoInstall(graph: AppGraph, state: OnboardingState) {
  // A failure stops the automatic start until Pipkin resumes, rather than relaunching in a loop.
  LaunchedEffect(state.shouldAutoInstall && state.currentError == null) {
    if (state.shouldAutoInstall && state.currentError == null) SetupProgress.install(graph)
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    if (state.shouldAutoInstall) SetupProgress.install(graph)
    // The user may be back from changing Termux's storage access in settings.
    if (state.canCheckStorage) SetupProgress.recheckStorage(graph)
  }
  LaunchedEffect(state.storageUnconfirmed) {
    if (state.storageUnconfirmed) SetupProgress.recheckStorage(graph)
  }
  LaunchedEffect(state.piReady) {
    if (state.piReady && graph.accountRepository.status.value == null) graph.accountRepository.refresh()
  }
}
