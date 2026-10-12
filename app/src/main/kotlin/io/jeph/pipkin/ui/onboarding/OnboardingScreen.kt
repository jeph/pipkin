package io.jeph.pipkin.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** What a tap on an onboarding-only step or the footer asks for; handled in [OnboardingScreen]. */
private sealed interface OnboardingAction {
  data object InstallPi : OnboardingAction

  data class Login(val providerId: String) : OnboardingAction

  data object AddProvider : OnboardingAction

  data object Finish : OnboardingAction
}

/**
 * Setup checklist: the [PermissionSteps], then the Pi install and sign-in.
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
  val onPermissionAction = rememberPermissionActions(state) { checks = DeviceChecks.read(context) }
  RecheckOnResume(graph, state) { checks = it }
  val onAction: (OnboardingAction) -> Unit = { action ->
    when (action) {
      OnboardingAction.InstallPi -> SetupProgress.install(graph)

      is OnboardingAction.Login -> loginProvider = action.providerId

      OnboardingAction.AddProvider -> onAddProvider()

      OnboardingAction.Finish -> {
        graph.preferences.setSetupComplete(true)
        onFinished()
      }
    }
  }

  SetupPage(header = { HeaderBar(title = stringResource(R.string.onboarding_title)) }) {
    PermissionSteps(state, onPermissionAction)
    AgentSteps(state, onAction)
    OnboardingFooter(canContinue = state.authStatus?.anyConfigured == true, onAction = onAction)
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

@Composable
internal fun rememberOnboardingState(graph: AppGraph, checks: DeviceChecks): OnboardingState {
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

/** The Pi install and sign-in, which follow the [PermissionSteps]. */
@Composable
private fun AgentSteps(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
  InstallPiStep(
    number = state.stepNumber(SetupStep.InstallPi),
    status = state.status(SetupStep.InstallPi),
    version = state.expectedPiVersion,
    progress = state.progress,
    error = state.errorFor(SetupStep.InstallPi),
    enabled = state.canLaunchHost,
    onInstall = { onAction(OnboardingAction.InstallPi) },
  )
  SignInStep(
    number = state.stepNumber(SetupStep.SignIn),
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
