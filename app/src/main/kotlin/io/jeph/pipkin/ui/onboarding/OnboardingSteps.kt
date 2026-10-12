package io.jeph.pipkin.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

@Composable
internal fun TermuxStep(
  number: Int,
  status: StepStatus,
  termux: TermuxInstall.Status,
  version: String?,
  onOpenFDroid: () -> Unit,
  onUninstall: () -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_termux_title), status = status) {
    when (termux) {
      TermuxInstall.Status.NotInstalled -> {
        StepText(stringResource(R.string.onboarding_termux_missing))
        PrimaryButton(stringResource(R.string.onboarding_termux_install), onOpenFDroid)
      }

      TermuxInstall.Status.PlayStore, TermuxInstall.Status.UnsupportedBuild -> {
        val text = if (termux == TermuxInstall.Status.PlayStore) {
          R.string.onboarding_termux_play
        } else {
          R.string.onboarding_termux_unsupported
        }
        StepText(stringResource(text))
        Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
          PrimaryButton(stringResource(R.string.onboarding_termux_uninstall), onUninstall)
          SecondaryButton(stringResource(R.string.onboarding_termux_open_fdroid), onOpenFDroid)
        }
      }

      TermuxInstall.Status.TooOld -> {
        StepText(stringResource(R.string.onboarding_termux_too_old))
        PrimaryButton(stringResource(R.string.onboarding_termux_update), onOpenFDroid)
      }

      TermuxInstall.Status.Supported -> StepText(stringResource(R.string.onboarding_termux_ready, version.orEmpty()))
    }
  }
}

/** The Android settings Pipkin asks for itself, as opposed to Termux's. */
@Composable
internal fun AndroidSettingsSteps(
  state: OnboardingState,
  notificationsBlocked: Boolean,
  onAllowNotifications: () -> Unit,
  onAllowBackground: () -> Unit,
) {
  if (state.checks.notificationsGranted != null) {
    NotificationsStep(
      number = state.stepNumber(SetupStep.Notifications),
      status = state.status(SetupStep.Notifications),
      blocked = notificationsBlocked,
      onAllow = onAllowNotifications,
    )
  }
  BackgroundStep(
    number = state.stepNumber(SetupStep.Background),
    status = state.status(SetupStep.Background),
    onAllow = onAllowBackground,
  )
}

@Composable
internal fun NotificationsStep(number: Int, status: StepStatus, blocked: Boolean, onAllow: () -> Unit) {
  StepCard(
    number = number,
    title = stringResource(R.string.onboarding_notifications_title),
    status = status,
    tag = stringResource(R.string.onboarding_recommended),
  ) {
    if (status == StepStatus.Done) {
      StepText(stringResource(R.string.onboarding_notifications_done))
    } else {
      StepText(stringResource(R.string.onboarding_notifications_body))
      val action = if (blocked) R.string.onboarding_open_settings else R.string.onboarding_notifications_allow
      PrimaryButton(stringResource(action), onAllow)
    }
  }
}

@Composable
internal fun BackgroundStep(number: Int, status: StepStatus, onAllow: () -> Unit) {
  StepCard(
    number = number,
    title = stringResource(R.string.onboarding_background_title),
    status = status,
    tag = stringResource(R.string.onboarding_recommended),
  ) {
    if (status == StepStatus.Done) {
      StepText(stringResource(R.string.onboarding_background_done))
    } else {
      StepText(stringResource(R.string.onboarding_background_body))
      PrimaryButton(stringResource(R.string.onboarding_background_allow), onAllow)
    }
  }
}

@Composable
internal fun RunCommandStep(
  number: Int,
  status: StepStatus,
  termuxInstalled: Boolean,
  blocked: Boolean,
  error: SetupError?,
  onAllow: () -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_run_command_title), status = status) {
    when {
      status == StepStatus.Done -> StepText(stringResource(R.string.onboarding_run_command_done))
      !termuxInstalled -> StepText(stringResource(R.string.onboarding_run_command_needs_termux))
      blocked -> StepText(stringResource(R.string.onboarding_run_command_blocked))
      else -> StepText(stringResource(R.string.onboarding_run_command_body))
    }
    error?.message?.let { StepText(it, color = pipkinColors.destructive) }
    if (status != StepStatus.Done) {
      val action = if (blocked) R.string.onboarding_open_settings else R.string.onboarding_run_command_allow
      PrimaryButton(stringResource(action), onAllow, enabled = termuxInstalled)
    }
  }
}

@Composable
internal fun TermuxSetupStep(
  number: Int,
  status: StepStatus,
  command: String,
  error: SetupError?,
  enabled: Boolean,
  onCopyAndOpen: () -> Unit,
  onCopy: () -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_setup_title), status = status) {
    error?.message?.let { StepText(it, color = pipkinColors.destructive) }
    if (status == StepStatus.Done) StepText(stringResource(R.string.onboarding_setup_done))
    StepText(stringResource(R.string.onboarding_setup_body))
    CommandBox(command = command, onCopy = onCopy)
    if (status == StepStatus.Done) {
      SecondaryButton(stringResource(R.string.onboarding_setup_action), onCopyAndOpen, enabled = enabled)
    } else {
      PrimaryButton(stringResource(R.string.onboarding_setup_action), onCopyAndOpen, enabled = enabled)
    }
  }
}

@Composable
internal fun InstallPiStep(
  number: Int,
  status: StepStatus,
  version: String,
  progress: List<String>,
  error: SetupError?,
  enabled: Boolean,
  onInstall: () -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_install_title), status = status) {
    when (status) {
      StepStatus.Running -> {
        StepText(stringResource(R.string.onboarding_install_running, version), color = pipkinColors.foreground)
        ProgressLines(progress)
      }

      StepStatus.Done -> StepText(stringResource(R.string.onboarding_install_done, version))

      StepStatus.Failed -> {
        StepText(error?.message ?: stringResource(R.string.onboarding_install_failed), color = pipkinColors.destructive)
        ProgressLines(progress)
        PrimaryButton(stringResource(R.string.onboarding_retry), onInstall, enabled = enabled)
      }

      StepStatus.Pending -> {
        StepText(stringResource(R.string.onboarding_install_body, version))
        PrimaryButton(stringResource(R.string.onboarding_install_action), onInstall, enabled = enabled)
      }
    }
  }
}

/** Sign-in choices; [enabled] once Pi is installed, since every option runs through it. */
@Composable
internal fun SignInStep(
  number: Int,
  status: StepStatus,
  authStatus: AuthStatus?,
  enabled: Boolean,
  onLogin: (providerId: String) -> Unit,
  onAddProvider: () -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_signin_title), status = status) {
    val body = if (enabled) R.string.onboarding_signin_body else R.string.onboarding_signin_needs_pi
    StepText(stringResource(body))
    Column {
      SignInRow(
        label = stringResource(R.string.onboarding_signin_claude),
        signedIn = authStatus.isSignedIn(ANTHROPIC),
        enabled = enabled,
        onClick = { onLogin(ANTHROPIC) },
      ) { ProviderLogo(ANTHROPIC) }
      SignInRow(
        label = stringResource(R.string.onboarding_signin_openai),
        signedIn = authStatus.isSignedIn(OPENAI_CODEX),
        enabled = enabled,
        onClick = { onLogin(OPENAI_CODEX) },
      ) { ProviderLogo(OPENAI_CODEX) }
      SignInRow(
        label = stringResource(R.string.onboarding_signin_api),
        signedIn = authStatus?.custom?.isNotEmpty() == true,
        enabled = enabled,
        onClick = onAddProvider,
      ) { LucideIcon(icon = R.drawable.ic_lucide_key_round, contentDescription = null) }
    }
  }
}

@Composable
private fun SignInRow(
  label: String,
  signedIn: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
) {
  val colors = pipkinColors
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 44.dp)
        .alpha(if (enabled) 1f else 0.5f)
        .clickable(enabled = enabled, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
      icon()
    }
    Text(
      text = label,
      modifier = Modifier.weight(1f),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (signedIn) {
      Row(
        modifier = Modifier.widthIn(max = 160.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
      ) {
        StatusDot(colors.dotSuccess)
        Text(
          text = stringResource(R.string.onboarding_signed_in),
          color = colors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.sm,
        )
      }
    }
  }
}

private fun AuthStatus?.isSignedIn(providerId: String): Boolean =
  this?.builtin?.any { it.id == providerId && it.configured } == true

private const val ANTHROPIC = "anthropic"
private const val OPENAI_CODEX = "openai-codex"
