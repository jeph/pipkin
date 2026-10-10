// POST_NOTIFICATIONS is a compile-time string; the notifications step only exists on API 33+.
@file:SuppressLint("InlinedApi")

package io.jeph.pipkin.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.termux.TermuxSetup
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** What a tap on the onboarding page asks for; handled in [OnboardingScreen]. */
private sealed interface OnboardingAction {
  data object OpenFDroid : OnboardingAction

  data object UninstallTermux : OnboardingAction

  data object AllowNotifications : OnboardingAction

  data object AllowRunCommand : OnboardingAction

  data object CopyAndOpenTermux : OnboardingAction

  data object CopyCommand : OnboardingAction

  data object InstallPi : OnboardingAction

  data class Login(val providerId: String) : OnboardingAction

  data object AddProvider : OnboardingAction

  data object Finish : OnboardingAction
}

/**
 * Setup checklist: Termux, permissions, the Termux setup command, the Pi install and sign-in.
 * Every step is re-checked on resume, so returning from Termux or system settings updates the page;
 * reopening it after setup shows finished steps as done.
 */
@Composable
internal fun OnboardingScreen(onFinished: () -> Unit, onAddProvider: () -> Unit) {
  val context = LocalContext.current
  val graph = remember(context) { context.appGraph }
  var checks by remember { mutableStateOf(DeviceChecks.read(context)) }
  var loginProvider by rememberSaveable { mutableStateOf<String?>(null) }
  val state = rememberOnboardingState(graph, checks)
  val requestPermission = rememberPermissionRequest(state.blockedPermissions) { checks = DeviceChecks.read(context) }
  RecheckOnResume(graph, state) { checks = it }

  OnboardingPage(state = state) { action ->
    when (action) {
      OnboardingAction.OpenFDroid -> context.startSafely { TermuxInstall.openFDroidPage(context) }

      OnboardingAction.UninstallTermux -> context.startSafely { TermuxInstall.requestUninstall(context) }

      OnboardingAction.AllowNotifications -> requestPermission(Manifest.permission.POST_NOTIFICATIONS)

      OnboardingAction.AllowRunCommand -> requestPermission(TermuxInstall.RUN_COMMAND_PERMISSION)

      OnboardingAction.CopyAndOpenTermux -> {
        SetupProgress.markTermuxCommandSent()
        context.startSafely { TermuxSetup.copyAndOpen(context, state.termuxCommand) }
      }

      OnboardingAction.CopyCommand -> copyCommand(context, state.termuxCommand)

      OnboardingAction.InstallPi -> SetupProgress.install(graph)

      is OnboardingAction.Login -> loginProvider = action.providerId

      OnboardingAction.AddProvider -> onAddProvider()

      OnboardingAction.Finish -> {
        graph.preferences.setSetupComplete(true)
        onFinished()
      }
    }
  }

  loginProvider?.let { LoginSheet(providerId = it, onDismiss = { loginProvider = null }) }
}

/**
 * Re-reads [DeviceChecks] on every resume, then refreshes sign-in status once Pi is installed or
 * starts the install once its prerequisites are met.
 */
@Composable
private fun RecheckOnResume(graph: AppGraph, state: OnboardingState, onChecks: (DeviceChecks) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    val fresh = DeviceChecks.read(context)
    onChecks(fresh)
    val current = state.copy(checks = fresh)
    if (current.piReady) {
      scope.launch { graph.accountRepository.refresh() }
    } else if (current.shouldAutoInstall) {
      SetupProgress.install(graph)
    }
  }
  // The host may report the installed Pi after resume, e.g. from the app's own session refresh.
  LaunchedEffect(state.piReady) {
    if (state.piReady && graph.accountRepository.status.value == null) graph.accountRepository.refresh()
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

@Composable
private fun rememberOnboardingState(graph: AppGraph, checks: DeviceChecks): OnboardingState {
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val lastFailure by graph.hostClient.lastFailure.collectAsStateWithLifecycle()
  val hostInstalling by graph.hostClient.installing.collectAsStateWithLifecycle()
  val setupComplete by graph.preferences.setupComplete.collectAsStateWithLifecycle()
  val authStatus by graph.accountRepository.status.collectAsStateWithLifecycle()
  val commandSent by SetupProgress.termuxCommandSent.collectAsStateWithLifecycle()
  val blockedPermissions by SetupProgress.blockedPermissions.collectAsStateWithLifecycle()
  val installing by SetupProgress.installing.collectAsStateWithLifecycle()
  val progress by SetupProgress.progress.collectAsStateWithLifecycle()
  val installError by SetupProgress.error.collectAsStateWithLifecycle()
  return OnboardingState(
    checks = checks,
    hello = hello,
    expectedPiVersion = graph.hostClient.expectedPiVersion,
    setupComplete = setupComplete,
    termuxCommandSent = commandSent,
    blockedPermissions = blockedPermissions,
    installing = installing || hostInstalling,
    progress = progress,
    // A setup failure from elsewhere (chat, sidebar) brought the user here; show it on its step.
    error = installError ?: lastFailure?.takeIf { it.needsSetup }?.toSetupError(),
    authStatus = authStatus,
  )
}

@Composable
private fun OnboardingPage(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
  Column(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(title = stringResource(R.string.onboarding_title))
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .navigationBarsPadding()
          .padding(PipkinTokens.Spacing.s16),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
      ) {
        OnboardingSteps(state, onAction)
        OnboardingFooter(canContinue = state.authStatus?.anyConfigured == true, onAction = onAction)
      }
    }
  }
}

@Composable
private fun OnboardingSteps(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
  val notificationsShown = state.checks.notificationsGranted != null
  val number = { step: SetupStep ->
    if (!notificationsShown && step > SetupStep.Notifications) step.ordinal else step.ordinal + 1
  }
  TermuxStep(
    number = number(SetupStep.Termux),
    status = state.status(SetupStep.Termux),
    termux = state.checks.termux,
    version = state.checks.termuxVersion,
    onOpenFDroid = { onAction(OnboardingAction.OpenFDroid) },
    onUninstall = { onAction(OnboardingAction.UninstallTermux) },
  )
  if (notificationsShown) {
    NotificationsStep(
      number = number(SetupStep.Notifications),
      status = state.status(SetupStep.Notifications),
      blocked = Manifest.permission.POST_NOTIFICATIONS in state.blockedPermissions,
      onAllow = { onAction(OnboardingAction.AllowNotifications) },
    )
  }
  RunCommandStep(
    number = number(SetupStep.RunCommand),
    status = state.status(SetupStep.RunCommand),
    termuxInstalled = state.checks.termux != TermuxInstall.Status.NotInstalled,
    blocked = TermuxInstall.RUN_COMMAND_PERMISSION in state.blockedPermissions,
    error = state.errorFor(SetupStep.RunCommand),
    onAllow = { onAction(OnboardingAction.AllowRunCommand) },
  )
  TermuxSetupStep(
    number = number(SetupStep.TermuxSetup),
    status = state.status(SetupStep.TermuxSetup),
    command = state.termuxCommand,
    error = state.errorFor(SetupStep.TermuxSetup),
    enabled = state.checks.termux == TermuxInstall.Status.Supported,
    onCopyAndOpen = { onAction(OnboardingAction.CopyAndOpenTermux) },
    onCopy = { onAction(OnboardingAction.CopyCommand) },
  )
  InstallPiStep(
    number = number(SetupStep.InstallPi),
    status = state.status(SetupStep.InstallPi),
    version = state.expectedPiVersion,
    progress = state.progress,
    error = state.errorFor(SetupStep.InstallPi),
    enabled = state.canLaunchHost,
    onInstall = { onAction(OnboardingAction.InstallPi) },
  )
  SignInStep(
    number = number(SetupStep.SignIn),
    status = state.status(SetupStep.SignIn),
    authStatus = state.authStatus,
    enabled = state.piReady,
    onLogin = { onAction(OnboardingAction.Login(it)) },
    onAddProvider = { onAction(OnboardingAction.AddProvider) },
  )
}

@Composable
private fun OnboardingFooter(canContinue: Boolean, onAction: (OnboardingAction) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = PipkinTokens.Spacing.s4),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(
      onClick = { onAction(OnboardingAction.Finish) },
      colors = ButtonDefaults.textButtonColors(contentColor = pipkinColors.foregroundMuted),
    ) {
      Text(text = stringResource(R.string.onboarding_skip), fontSize = PipkinTokens.FontSize.base)
    }
    PrimaryButton(
      text = stringResource(R.string.onboarding_continue),
      onClick = { onAction(OnboardingAction.Finish) },
      enabled = canContinue,
    )
  }
}

private fun copyCommand(context: Context, command: String) {
  context.getSystemService(ClipboardManager::class.java)
    .setPrimaryClip(ClipData.newPlainText("Termux command", command))
}

private fun openNotificationSettings(context: Context) {
  context.startActivity(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
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
