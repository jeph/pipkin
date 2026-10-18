package io.jeph.pipkin.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.auth.ProviderLogo
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * One provider Pi ships: its sign-in state, which of its models show and their settings, and
 * sign-out (or, for an API key, removing the key). Model edits stay a draft until Save; [onBack]
 * asks first when there are unsaved edits.
 */
@Composable
internal fun SubscriptionScreen(
  form: SubscriptionForm,
  onBack: () -> Unit,
  onAddModels: () -> Unit,
  onOpenModel: (modelId: String) -> Unit,
) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val status by accounts.status.collectAsStateWithLifecycle()
  val refreshError by accounts.refreshError.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarStackState() }
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var signingIn by rememberSaveable { mutableStateOf(false) }
  var confirmDiscard by remember { mutableStateOf(false) }
  val failed = stringResource(R.string.provider_failed)
  val id = form.providerId
  val subscription = subscriptions.firstOrNull { it.id == id }
  val signedIn = status?.isSignedIn(id)
  // Reads Pi's files afresh, so changes made outside Pipkin (for example by the agent) show here.
  LaunchedEffect(form) { accounts.refresh() }
  LaunchedEffect(status) { status?.builtin?.firstOrNull { it.id == id }?.let(form::prefill) }
  BackHandler(enabled = form.dirty) { confirmDiscard = true }
  val signOut: () -> Unit = {
    busy = true
    scope.launch {
      val error = signOut(accounts, id, failed)
      busy = false
      if (error == null) onBack() else snackbar.show(error)
    }
  }
  SettingsPage(
    title = subscription?.let { stringResource(it.title) } ?: id,
    onBack = { if (form.dirty) confirmDiscard = true else onBack() },
    snackbar = snackbar,
  ) {
    SubscriptionHeader(id, loading = busy || (signedIn == null && refreshError == null), signedIn) { signingIn = true }
    if (signedIn == true) {
      SubscriptionModels(
        form = form,
        busy = busy,
        usesApiKey = subscription?.usesApiKey == true,
        onSave = { scope.launch { if (form.save(accounts, failed)) onBack() } },
        onSignOut = signOut,
        onAddModels = onAddModels,
        onOpenModel = onOpenModel,
      )
    }
    listOfNotNull(status?.settingsError?.localized(), status?.modelsError?.localized(), refreshError).forEach {
      SettingsCaption(text = it, color = pipkinColors.destructive)
    }
  }
  if (signingIn) LoginSheet(providerId = id, onDismiss = { signingIn = false })
  if (confirmDiscard) {
    DiscardDialog(
      onDiscard = {
        confirmDiscard = false
        onBack()
      },
      onDismiss = { confirmDiscard = false },
    )
  }
}

/** The provider's logo and name, with its sign-in state. */
@Composable
private fun SubscriptionHeader(id: String, loading: Boolean, signedIn: Boolean?, onSignIn: () -> Unit) {
  val subscription = subscriptions.firstOrNull { it.id == id }
  SettingsSection(title = null) {
    SettingsRow(
      title = subscription?.let { stringResource(it.title) } ?: id,
      subtitle = subscription?.let { stringResource(it.subtitle) },
      leading = { ProviderLogo(id) },
    ) {
      SubscriptionStatus(loading, signedIn, usesApiKey = subscription?.usesApiKey == true, onSignIn)
    }
  }
}

/** A signed-in provider's models, Save, and sign-out. */
@Composable
private fun SubscriptionModels(
  form: SubscriptionForm,
  busy: Boolean,
  usesApiKey: Boolean,
  onSave: () -> Unit,
  onSignOut: () -> Unit,
  onAddModels: () -> Unit,
  onOpenModel: (modelId: String) -> Unit,
) {
  ModelsSection(
    draft = form,
    emptyText = stringResource(R.string.subscription_models_empty),
    onAddModels = onAddModels,
    onOpenModel = onOpenModel,
  )
  form.formError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
  Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = form.canSave) {
    if (form.saving) {
      SimmeringPot(tint = pipkinColors.accentForeground)
    } else {
      Text(stringResource(R.string.provider_save))
    }
  }
  SettingsSection(title = null) {
    SettingsRow(
      title = stringResource(if (usesApiKey) R.string.settings_remove_key else R.string.settings_sign_out),
      titleColor = pipkinColors.destructive,
      enabled = !busy,
      onClick = onSignOut,
    )
  }
}

/** Null once signed out, else why it failed. */
private suspend fun signOut(accounts: AccountRepository, id: String, fallbackError: String): String? = try {
  accounts.logout(id)
  null
} catch (failure: IOException) {
  failure.message ?: fallbackError
} catch (rejected: HostRejected) {
  rejected.error
}

/**
 * The loading pot while [loading], the signed-in badge, or a sign-in button; nothing when status is
 * unknown. An API-key provider shows its key as saved and offers to add one instead.
 */
@Composable
private fun SubscriptionStatus(loading: Boolean, signedIn: Boolean?, usesApiKey: Boolean, onSignIn: () -> Unit) {
  val signedInLabel = stringResource(if (usesApiKey) R.string.settings_key_saved else R.string.settings_signed_in)
  val signInLabel = stringResource(if (usesApiKey) R.string.settings_add_key else R.string.settings_sign_in)
  when {
    loading -> SimmeringPot()
    signedIn == true -> SignedInBadge(signedInLabel)
    signedIn == false -> SmallButton(label = signInLabel, onClick = onSignIn)
  }
}

@Composable
private fun SignedInBadge(label: String) {
  val colors = pipkinColors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    StatusDot(color = colors.dotSuccess)
    Text(
      text = label,
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
  }
}
