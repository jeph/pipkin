package io.jeph.pipkin.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.jeph.pipkin.R
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Which Termux is installed, and how to get, update or replace it. */
@Composable
internal fun TermuxStep(
  number: Int,
  status: StepStatus,
  termux: TermuxInstall.Status,
  version: String?,
  onAction: (PermissionAction) -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_termux_title), status = status) {
    when (termux) {
      TermuxInstall.Status.NotInstalled -> {
        val playAvailable = TermuxInstall.playBuildAvailable
        val text = if (playAvailable) {
          R.string.onboarding_termux_missing
        } else {
          R.string.onboarding_termux_missing_fdroid_only
        }
        StepText(stringResource(text))
        TermuxSecurityNote()
        InstallButtons(playAvailable, onAction)
      }

      TermuxInstall.Status.UnsupportedBuild -> {
        StepText(stringResource(R.string.onboarding_termux_unsupported))
        TermuxSecurityNote()
        PrimaryButton(
          text = stringResource(R.string.onboarding_termux_uninstall),
          onClick = { onAction(PermissionAction.UninstallTermux) },
        )
        InstallButtons(TermuxInstall.playBuildAvailable, onAction, primary = false)
      }

      TermuxInstall.Status.FDroidTooOld -> {
        StepText(stringResource(R.string.onboarding_termux_too_old_fdroid))
        PrimaryButton(
          text = stringResource(R.string.onboarding_termux_update_fdroid),
          onClick = { onAction(PermissionAction.OpenFDroid) },
        )
      }

      TermuxInstall.Status.PlayTooOld -> {
        StepText(stringResource(R.string.onboarding_termux_too_old_play))
        PrimaryButton(
          text = stringResource(R.string.onboarding_termux_update_play),
          onClick = { onAction(PermissionAction.OpenPlay) },
        )
      }

      TermuxInstall.Status.FDroid ->
        StepText(stringResource(R.string.onboarding_termux_ready_fdroid, version.orEmpty()))

      TermuxInstall.Status.Play -> StepText(stringResource(R.string.onboarding_termux_ready_play, version.orEmpty()))
    }
  }
}

/** Where to get Termux; the Play build needs Android 11, so older phones only get F-Droid. */
@Composable
private fun InstallButtons(playAvailable: Boolean, onAction: (PermissionAction) -> Unit, primary: Boolean = true) {
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    val fdroid = stringResource(R.string.onboarding_termux_get_fdroid)
    if (primary) {
      PrimaryButton(fdroid, { onAction(PermissionAction.OpenFDroid) })
    } else {
      SecondaryButton(fdroid, { onAction(PermissionAction.OpenFDroid) })
    }
    if (playAvailable) {
      SecondaryButton(stringResource(R.string.onboarding_termux_get_play), { onAction(PermissionAction.OpenPlay) })
    }
  }
}

/** Why Pipkin accepts only the official F-Droid and Play builds, shown until one is installed. */
@Composable
private fun TermuxSecurityNote() {
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface2, RoundedCornerShape(PipkinTokens.Radius.md))
      .padding(PipkinTokens.Spacing.s12),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8),
  ) {
    LucideIcon(icon = R.drawable.ic_lucide_shield_check, contentDescription = null, tint = colors.foreground)
    Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s4)) {
      Text(
        text = stringResource(R.string.onboarding_termux_security_title),
        color = colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.Medium,
      )
      StepText(stringResource(R.string.onboarding_termux_security_body))
    }
  }
}

/** Play build only: Termux exempt from battery optimization, so the helper keeps running. */
@Composable
internal fun TermuxBackgroundStep(number: Int, status: StepStatus, onAllow: () -> Unit) {
  StepCard(
    number = number,
    title = stringResource(R.string.onboarding_termux_background_title),
    status = status,
    tag = stringResource(R.string.onboarding_recommended),
  ) {
    if (status == StepStatus.Done) {
      StepText(stringResource(R.string.onboarding_termux_background_done))
    } else {
      StepText(stringResource(R.string.onboarding_termux_background_body))
      PrimaryButton(stringResource(R.string.onboarding_termux_background_allow), onAllow)
    }
  }
}

/**
 * The one-paste setup command for the installed [termux] build. The Play build's carries a fresh pairing token on
 * each copy, so before the first copy [command] is null and the box explains where it will appear.
 */
@Composable
internal fun TermuxSetupStep(
  number: Int,
  status: StepStatus,
  command: String?,
  termux: TermuxInstall.Status,
  termuxChanged: Boolean,
  error: SetupError?,
  onCopyAndOpen: () -> Unit,
  onCopy: () -> Unit,
) {
  StepCard(number = number, title = stringResource(R.string.onboarding_setup_title), status = status) {
    error?.message?.let { StepText(it, color = pipkinColors.destructive) }
    if (termuxChanged && error == null) {
      StepText(stringResource(R.string.onboarding_setup_changed), color = pipkinColors.destructive)
    }
    if (status == StepStatus.Done) StepText(stringResource(R.string.onboarding_setup_done))
    val body = if (termux.isPlay) R.string.onboarding_setup_body_play else R.string.onboarding_setup_body
    StepText(stringResource(body))
    if (command != null) {
      CommandBox(command = command, onCopy = onCopy)
    } else {
      StepText(stringResource(R.string.onboarding_setup_play_pending))
    }
    val enabled = termux == TermuxInstall.Status.FDroid || termux == TermuxInstall.Status.Play
    if (status == StepStatus.Done) {
      SecondaryButton(stringResource(R.string.onboarding_setup_action), onCopyAndOpen, enabled = enabled)
    } else {
      PrimaryButton(stringResource(R.string.onboarding_setup_action), onCopyAndOpen, enabled = enabled)
    }
  }
}
