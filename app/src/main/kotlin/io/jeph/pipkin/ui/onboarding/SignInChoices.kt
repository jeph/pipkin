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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.localmodel.catalog.LocalModelCatalog
import io.jeph.pipkin.ui.auth.ProviderLogo
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.settings.isSignedIn
import io.jeph.pipkin.ui.settings.subscriptions
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * The ways to reach a model: the providers Pi ships that Pipkin connects, an OpenAI-compatible API, or the experimental
 * models that run on the phone, ready once one is downloaded with local models on.
 */
@Composable
internal fun SignInChoices(authStatus: AuthStatus?, enabled: Boolean, onAction: (SetupAction) -> Unit) {
  val colors = pipkinColors
  val graph = LocalContext.current.appGraph
  val localModelsOn by graph.preferences.localModelsEnabled.collectAsStateWithLifecycle()
  val localModels by graph.localModels.store.installed.collectAsStateWithLifecycle()
  val shape = RoundedCornerShape(PipkinTokens.Radius.xl)
  Column(
    modifier = Modifier.fillMaxWidth().background(colors.surface1, shape).padding(vertical = PipkinTokens.Spacing.s4),
  ) {
    subscriptions.forEach { subscription ->
      SignInRow(
        label = stringResource(subscription.title),
        signedIn = authStatus?.isSignedIn(subscription.id) == true,
        enabled = enabled,
        onClick = { onAction(SetupAction.Login(subscription.id)) },
      ) { ProviderLogo(subscription.id) }
    }
    SignInRow(
      label = stringResource(R.string.onboarding_signin_api),
      signedIn = authStatus?.custom.orEmpty().any { it.id != LocalModelCatalog.PROVIDER_ID },
      enabled = enabled,
      onClick = { onAction(SetupAction.AddProvider) },
    ) { LucideIcon(icon = R.drawable.ic_lucide_key_round, contentDescription = null) }
    SignInRow(
      label = stringResource(R.string.onboarding_signin_local),
      signedIn = localModelsOn && localModels.isNotEmpty(),
      enabled = enabled,
      onClick = { onAction(SetupAction.OpenLocalModels) },
    ) { LucideIcon(icon = R.drawable.ic_lucide_brain, contentDescription = null) }
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
      .heightIn(min = 52.dp)
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
      fontWeight = FontWeight.SemiBold,
      // Two lines fit "OpenAI-compatible API" at the largest font size.
      maxLines = 2,
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
