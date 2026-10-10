package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.auth.ProviderLogo
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.Spinner
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.launch

/** A subscription Pipkin signs in to with OAuth. */
private data class Subscription(val id: String, @StringRes val title: Int, @StringRes val subtitle: Int)

private val subscriptions = listOf(
  Subscription("anthropic", R.string.settings_claude, R.string.settings_claude_subtitle),
  Subscription("openai-codex", R.string.settings_openai, R.string.settings_openai_subtitle),
)

/** Subscription sign-in rows, custom OpenAI-compatible providers and the add-provider row. */
@Composable
internal fun AccountsSection(
  accounts: AccountRepository,
  snackbar: SnackbarHostState,
  onSignIn: (providerId: String) -> Unit,
  onEditProvider: (id: String?) -> Unit,
) {
  val status by accounts.status.collectAsStateWithLifecycle()
  val refreshError by accounts.refreshError.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var signingOut by remember { mutableStateOf<String?>(null) }
  val signOutFailed = stringResource(R.string.provider_failed)
  val onSignOut: (String) -> Unit = { id ->
    signingOut = id
    scope.launch {
      val error = try {
        accounts.logout(id)
        null
      } catch (failure: IOException) {
        failure.message ?: signOutFailed
      } catch (rejected: HostRejected) {
        rejected.error
      } finally {
        signingOut = null
      }
      if (error != null) snackbar.showSnackbar(error)
    }
  }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    SettingsSection(title = stringResource(R.string.settings_section_accounts)) {
      subscriptions.forEachIndexed { index, subscription ->
        if (index > 0) SettingsDivider()
        SubscriptionRow(
          subscription = subscription,
          configured = status?.builtin?.firstOrNull { it.id == subscription.id }?.configured,
          loading = (status == null && refreshError == null) || signingOut == subscription.id,
          onSignIn = { onSignIn(subscription.id) },
          onSignOut = { onSignOut(subscription.id) },
        )
      }
      status?.custom.orEmpty().forEach { provider ->
        SettingsDivider()
        SettingsRow(title = provider.id, subtitle = provider.baseUrl, onClick = { onEditProvider(provider.id) })
      }
    }
    DashedActionRow(
      label = stringResource(R.string.settings_add_provider),
      icon = R.drawable.ic_lucide_plus,
      onClick = { onEditProvider(null) },
    )
    status?.modelsError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
    refreshError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
  }
}

@Composable
private fun SubscriptionRow(
  subscription: Subscription,
  configured: Boolean?,
  loading: Boolean,
  onSignIn: () -> Unit,
  onSignOut: () -> Unit,
) {
  SettingsRow(
    title = stringResource(subscription.title),
    subtitle = stringResource(subscription.subtitle),
    leading = { ProviderLogo(subscription.id) },
  ) {
    when {
      loading -> Spinner()
      configured == true -> SignedInControl(onSignOut = onSignOut)
      else -> SmallButton(label = stringResource(R.string.settings_sign_in), onClick = onSignIn)
    }
  }
}

@Composable
private fun SignedInControl(onSignOut: () -> Unit) {
  val colors = pipkinColors
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    StatusDot(color = colors.dotSuccess)
    Text(
      text = stringResource(R.string.settings_signed_in),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
    Box {
      IconButton28(
        icon = R.drawable.ic_lucide_ellipsis,
        contentDescription = stringResource(R.string.settings_account_options),
        onClick = { menuOpen = true },
        tint = colors.foregroundMuted,
      )
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.surface2) {
        DropdownMenuItem(
          text = { Text(text = stringResource(R.string.settings_sign_out), color = colors.destructive) },
          onClick = {
            menuOpen = false
            onSignOut()
          },
        )
      }
    }
  }
}
