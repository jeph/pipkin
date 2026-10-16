package io.jeph.pipkin.ui.onboarding

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
import io.jeph.pipkin.ui.auth.ProviderLogo
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** The ways to reach a model: the two subscriptions Pipkin signs in to, or an OpenAI-compatible API. */
@Composable
internal fun SignInChoices(authStatus: AuthStatus?, enabled: Boolean, onAction: (SetupAction) -> Unit) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.lg)
  Column(
    modifier = Modifier.fillMaxWidth().background(colors.surface1, shape).padding(vertical = PipkinTokens.Spacing.s4),
  ) {
    SignInRow(
      label = stringResource(R.string.onboarding_signin_claude),
      signedIn = authStatus.isSignedIn(ANTHROPIC),
      enabled = enabled,
      onClick = { onAction(SetupAction.Login(ANTHROPIC)) },
    ) { ProviderLogo(ANTHROPIC) }
    SignInRow(
      label = stringResource(R.string.onboarding_signin_openai),
      signedIn = authStatus.isSignedIn(OPENAI_CODEX),
      enabled = enabled,
      onClick = { onAction(SetupAction.Login(OPENAI_CODEX)) },
    ) { ProviderLogo(OPENAI_CODEX) }
    SignInRow(
      label = stringResource(R.string.onboarding_signin_api),
      signedIn = authStatus?.custom?.isNotEmpty() == true,
      enabled = enabled,
      onClick = { onAction(SetupAction.AddProvider) },
    ) { LucideIcon(icon = R.drawable.ic_lucide_key_round, contentDescription = null) }
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
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .alpha(if (enabled) 1f else 0.5f)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = PipkinTokens.Spacing.s16),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
    Text(
      text = label,
      modifier = Modifier.weight(1f),
      color = colors.foreground,
      fontSize = PipkinTokens.FontSize.base,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (signedIn) {
      LucideIcon(
        icon = R.drawable.ic_lucide_circle_check,
        contentDescription = stringResource(R.string.onboarding_signed_in),
        tint = colors.accent,
      )
    } else {
      LucideIcon(icon = R.drawable.ic_lucide_chevron_right, contentDescription = null, tint = colors.foregroundMuted)
    }
  }
}

private fun AuthStatus?.isSignedIn(providerId: String): Boolean =
  this?.builtin?.any { it.id == providerId && it.configured } == true

private const val ANTHROPIC = "anthropic"
private const val OPENAI_CODEX = "openai-codex"
