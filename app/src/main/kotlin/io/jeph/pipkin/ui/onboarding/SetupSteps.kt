package io.jeph.pipkin.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.ui.auth.ProviderLogo
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A step's button; the first of a [StepSpec]'s is its main action. */
internal data class StepButton(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * One setup step's content, rendered as a full page in onboarding ([OnboardingScreen]) and as a card in Settings →
 * Permissions ([PermissionsScreen]). [extra] holds what's more than text and buttons: a command, install output or
 * sign-in choices.
 */
internal data class StepSpec(
  val step: SetupStep,
  @param:DrawableRes val icon: Int,
  val title: String,
  val body: String,
  val status: StepStatus,
  val buttons: List<StepButton> = emptyList(),
  val error: String? = null,
  /** Shown with a spinner while Pipkin waits on something it can't hurry, such as Termux finishing the setup. */
  val waiting: String? = null,
  /** A quieter line under the step's state, such as what to do if the setup command stops partway. */
  val note: String? = null,
  val extra: (@Composable () -> Unit)? = null,
)

/** The content of [step] for [state]; taps go to [onAction]. The installed Termux build picks the wording. */
@Composable
internal fun stepSpec(step: SetupStep, state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec =
  when (step) {
    SetupStep.Termux -> termuxSpec(state, onAction)

    SetupStep.RunCommand -> runCommandSpec(state, onAction)

    SetupStep.TermuxSetup -> termuxSetupSpec(state, onAction)

    SetupStep.Notifications -> notificationsSpec(state, onAction)

    SetupStep.Background -> permissionSpec(
      step = step,
      state = state,
      icon = R.drawable.ic_lucide_battery_charging,
      title = R.string.onboarding_background_title,
      body = R.string.onboarding_background_body,
      done = R.string.onboarding_background_done,
      action = StepButton(stringResource(R.string.onboarding_allow)) { onAction(SetupAction.AllowBackground) },
    )

    SetupStep.TermuxBackground -> permissionSpec(
      step = step,
      state = state,
      icon = R.drawable.ic_lucide_battery_charging,
      title = R.string.onboarding_termux_background_title,
      body = R.string.onboarding_termux_background_body,
      done = R.string.onboarding_termux_background_done,
      action = StepButton(stringResource(R.string.onboarding_termux_background_allow)) {
        onAction(SetupAction.AllowTermuxBackground)
      },
    )

    SetupStep.InstallPi -> installPiSpec(state, onAction)

    SetupStep.SignIn -> signInSpec(state, onAction)
  }

@Composable
private fun permissionSpec(
  step: SetupStep,
  state: OnboardingState,
  @DrawableRes icon: Int,
  title: Int,
  body: Int,
  done: Int,
  action: StepButton,
): StepSpec {
  val status = state.status(step)
  return StepSpec(
    step = step,
    icon = icon,
    title = stringResource(title),
    body = stringResource(if (status == StepStatus.Done) done else body),
    status = status,
    buttons = if (status == StepStatus.Done) emptyList() else listOf(action),
  )
}

@Composable
private fun notificationsSpec(state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec {
  val blocked = android.Manifest.permission.POST_NOTIFICATIONS in state.blockedPermissions
  val label = if (blocked) R.string.onboarding_open_settings else R.string.onboarding_allow
  return permissionSpec(
    step = SetupStep.Notifications,
    state = state,
    icon = R.drawable.ic_lucide_bell,
    title = R.string.onboarding_notifications_title,
    body = R.string.onboarding_notifications_body,
    done = R.string.onboarding_notifications_done,
    action = StepButton(stringResource(label)) { onAction(SetupAction.AllowNotifications) },
  )
}

@Composable
private fun termuxSpec(state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec {
  val termux = state.checks.termux
  val version = state.checks.termuxVersion.orEmpty()
  val fdroid = StepButton(stringResource(R.string.onboarding_termux_get_fdroid)) { onAction(SetupAction.OpenFDroid) }
  val play = StepButton(stringResource(R.string.onboarding_termux_get_play)) { onAction(SetupAction.OpenPlay) }
  // The Play build needs Android 11, so older phones only get F-Droid.
  val stores = if (TermuxInstall.playBuildAvailable) listOf(fdroid, play) else listOf(fdroid)
  val (title, body, buttons) = when (termux) {
    TermuxInstall.Status.NotInstalled -> Triple(
      R.string.onboarding_termux_install_title,
      if (TermuxInstall.playBuildAvailable) {
        R.string.onboarding_termux_missing
      } else {
        R.string.onboarding_termux_missing_fdroid_only
      },
      stores,
    )

    TermuxInstall.Status.UnsupportedBuild -> Triple(
      R.string.onboarding_termux_replace_title,
      R.string.onboarding_termux_unsupported,
      listOf(
        StepButton(stringResource(R.string.onboarding_termux_uninstall)) {
          onAction(SetupAction.UninstallTermux)
        },
      ) +
        stores,
    )

    TermuxInstall.Status.FDroidTooOld -> Triple(
      R.string.onboarding_termux_update_title,
      R.string.onboarding_termux_too_old_fdroid,
      listOf(StepButton(stringResource(R.string.onboarding_termux_update_fdroid)) { onAction(SetupAction.OpenFDroid) }),
    )

    TermuxInstall.Status.PlayTooOld -> Triple(
      R.string.onboarding_termux_update_title,
      R.string.onboarding_termux_too_old_play,
      listOf(StepButton(stringResource(R.string.onboarding_termux_update_play)) { onAction(SetupAction.OpenPlay) }),
    )

    TermuxInstall.Status.FDroid ->
      Triple(R.string.onboarding_termux_title, R.string.onboarding_termux_ready_fdroid, emptyList())

    TermuxInstall.Status.Play ->
      Triple(R.string.onboarding_termux_title, R.string.onboarding_termux_ready_play, emptyList())
  }
  val trusted = termux == TermuxInstall.Status.FDroid || termux == TermuxInstall.Status.Play
  return StepSpec(
    step = SetupStep.Termux,
    icon = R.drawable.ic_lucide_square_terminal,
    title = stringResource(title),
    body = stringResource(body, version),
    status = state.status(SetupStep.Termux),
    buttons = buttons,
    extra = if (trusted) null else ({ SecurityNote() }),
  )
}

@Composable
private fun runCommandSpec(state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec {
  val status = state.status(SetupStep.RunCommand)
  val blocked = TermuxInstall.RUN_COMMAND_PERMISSION in state.blockedPermissions
  val body = when {
    status == StepStatus.Done -> R.string.onboarding_run_command_done
    blocked -> R.string.onboarding_run_command_blocked
    else -> R.string.onboarding_run_command_body
  }
  val label = if (blocked) R.string.onboarding_open_settings else R.string.onboarding_allow
  // Android can only grant a permission an installed app defines: F-Droid's Termux.
  val termuxDefinesIt = state.checks.termux.isTrusted && !state.checks.termux.isPlay
  return StepSpec(
    step = SetupStep.RunCommand,
    icon = R.drawable.ic_lucide_terminal,
    title = stringResource(R.string.onboarding_run_command_title),
    body = stringResource(body),
    status = status,
    buttons = if (status == StepStatus.Done) {
      emptyList()
    } else {
      listOf(StepButton(stringResource(label), termuxDefinesIt) { onAction(SetupAction.AllowRunCommand) })
    },
  )
}

/**
 * The one setup command for either build. A failure that names its own fix (`termux-setup-storage`) shows that
 * command instead, since it's short and says what it does.
 */
@Composable
private fun termuxSetupSpec(state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec {
  val status = state.status(SetupStep.TermuxSetup)
  val error = state.errorFor(SetupStep.TermuxSetup)
  val trusted = state.checks.termux == TermuxInstall.Status.FDroid || state.checks.termux == TermuxInstall.Status.Play
  // After a reinstall, every failure here comes down to the setup the reinstall removed.
  val problem = if (state.checks.termuxChanged) stringResource(R.string.onboarding_setup_changed) else error?.message
  val waiting = when {
    status == StepStatus.Running -> stringResource(R.string.onboarding_setup_connecting)
    state.waitingForTermux && problem == null -> stringResource(R.string.onboarding_setup_waiting)
    else -> null
  }
  val literal = state.setupLiteral
  return StepSpec(
    step = SetupStep.TermuxSetup,
    icon = R.drawable.ic_lucide_square_terminal,
    title = stringResource(R.string.onboarding_setup_title),
    body = stringResource(
      if (status ==
        StepStatus.Done
      ) {
        R.string.onboarding_setup_done
      } else {
        R.string.onboarding_setup_body
      },
    ),
    status = status,
    buttons = listOf(
      StepButton(stringResource(R.string.onboarding_setup_action), trusted) { onAction(SetupAction.CopyAndOpenTermux) },
    ),
    error = problem,
    waiting = waiting,
    note = if (literal == null &&
      status != StepStatus.Done
    ) {
      stringResource(R.string.onboarding_setup_retry_note)
    } else {
      null
    },
    extra = literal?.let { { CommandBox(it) } },
  )
}

@Composable
private fun installPiSpec(state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec {
  val status = state.status(SetupStep.InstallPi)
  val version = state.expectedPiVersion
  val install = StepButton(stringResource(R.string.onboarding_install_action), state.canLaunchHost) {
    onAction(SetupAction.InstallPi)
  }
  val retry =
    StepButton(stringResource(R.string.onboarding_retry), state.canLaunchHost) { onAction(SetupAction.InstallPi) }
  return StepSpec(
    step = SetupStep.InstallPi,
    icon = R.drawable.ic_lucide_bot,
    title = stringResource(R.string.onboarding_install_title),
    body = when (status) {
      StepStatus.Done -> stringResource(R.string.onboarding_install_done, version)
      else -> stringResource(R.string.onboarding_install_body, version)
    },
    status = status,
    buttons = when (status) {
      StepStatus.Pending -> listOf(install)
      StepStatus.Failed -> listOf(retry)
      else -> emptyList()
    },
    error = state.errorFor(SetupStep.InstallPi)?.let {
      it.message ?: stringResource(R.string.onboarding_install_failed)
    },
    // `ensure` reports progress only when it installs, starting with `npm ci`; until then it's checking the agent.
    waiting = when {
      status != StepStatus.Running -> null
      state.progress.isEmpty() -> stringResource(R.string.onboarding_install_checking)
      else -> stringResource(R.string.onboarding_install_running)
    },
    extra = if (state.progress.isEmpty() || status == StepStatus.Done) null else ({ ProgressLines(state.progress) }),
  )
}

@Composable
private fun signInSpec(state: OnboardingState, onAction: (SetupAction) -> Unit): StepSpec {
  val enabled = state.piReady
  return StepSpec(
    step = SetupStep.SignIn,
    icon = R.drawable.ic_lucide_key_round,
    title = stringResource(R.string.onboarding_signin_title),
    body = stringResource(if (enabled) R.string.onboarding_signin_body else R.string.onboarding_signin_needs_pi),
    status = state.status(SetupStep.SignIn),
    extra = { SignInChoices(state.authStatus, enabled, onAction) },
  )
}

/** Why Pipkin accepts only the official F-Droid and Play builds, shown until one is installed. */
@Composable
private fun SecurityNote() {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface1, RoundedCornerShape(PipkinTokens.Radius.lg))
      .padding(PipkinTokens.Spacing.s12),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_shield_check, contentDescription = null, tint = colors.foregroundMuted)
    StepText(stringResource(R.string.onboarding_termux_security))
  }
}
