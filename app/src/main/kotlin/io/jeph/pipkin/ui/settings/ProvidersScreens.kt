package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.auth.ProviderLogo
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.components.StatusDot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.launch

/** The added providers: signed-in providers Pi ships, then custom OpenAI-compatible endpoints. */
@Composable
internal fun ProvidersScreen(
  onBack: () -> Unit,
  onAdd: () -> Unit,
  onOpenSubscription: (id: String) -> Unit,
  onEditProvider: (id: String) -> Unit,
) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val status by accounts.status.collectAsStateWithLifecycle()
  val refreshError by accounts.refreshError.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { accounts.refresh() }
  SettingsPage(title = stringResource(R.string.providers_title), onBack = onBack) {
    val current = status
    val signedIn = subscriptions.filter { current?.isSignedIn(it.id) == true }
    val custom = current?.custom.orEmpty()
    when {
      current == null && refreshError == null ->
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SimmeringPot() }

      signedIn.isEmpty() && custom.isEmpty() -> SettingsCaption(text = stringResource(R.string.providers_empty))

      else -> SettingsSection(title = null) {
        signedIn.forEachIndexed { index, subscription ->
          if (index > 0) SettingsDivider()
          SettingsRow(
            title = stringResource(subscription.title),
            subtitle = stringResource(subscription.subtitle),
            onClick = { onOpenSubscription(subscription.id) },
            leading = { ProviderLogo(subscription.id) },
          ) { Chevron() }
        }
        custom.forEachIndexed { index, provider ->
          if (index > 0 || signedIn.isNotEmpty()) SettingsDivider()
          SettingsRow(
            title = provider.id,
            subtitle = provider.baseUrl,
            onClick = { onEditProvider(provider.id) },
            leading = { RowIcon(R.drawable.ic_lucide_key_round) },
          ) { Chevron() }
        }
      }
    }
    DashedActionRow(label = stringResource(R.string.providers_add), icon = R.drawable.ic_lucide_plus, onClick = onAdd)
    current?.modelsError?.let { SettingsCaption(text = it.localized(), color = pipkinColors.destructive) }
    refreshError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
  }
}

/**
 * The kinds of provider to add. A provider Pi ships signs in (or takes its API key) over a sheet and
 * [onAdded] runs with its id once Pi holds its credential; the OpenAI-compatible option continues to the
 * provider editor.
 */
@Composable
internal fun AddProviderScreen(onBack: () -> Unit, onAddCompatible: () -> Unit, onAdded: (providerId: String) -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val status by accounts.status.collectAsStateWithLifecycle()
  val refreshError by accounts.refreshError.collectAsStateWithLifecycle()
  var signingIn by rememberSaveable { mutableStateOf<String?>(null) }
  // A screen restored after process death is composed without the providers list that loads status.
  LaunchedEffect(Unit) { if (accounts.status.value == null) accounts.refresh() }
  SettingsPage(title = stringResource(R.string.providers_add), onBack = onBack) {
    SettingsSection(title = null) {
      subscriptions.forEach { subscription ->
        val added = status?.isSignedIn(subscription.id) == true
        SettingsRow(
          title = stringResource(subscription.title),
          subtitle = stringResource(subscription.subtitle),
          enabled = !added,
          onClick = { signingIn = subscription.id },
          leading = { ProviderLogo(subscription.id) },
        ) {
          if (added) SettingsValue(stringResource(R.string.providers_added)) else Chevron()
        }
        SettingsDivider()
      }
      SettingsRow(
        title = stringResource(R.string.providers_compatible),
        subtitle = stringResource(R.string.providers_compatible_subtitle),
        onClick = onAddCompatible,
        leading = { RowIcon(R.drawable.ic_lucide_key_round) },
      ) { Chevron() }
    }
    refreshError?.let { SettingsCaption(text = it, color = pipkinColors.destructive) }
  }
  signingIn?.let { id ->
    LoginSheet(
      providerId = id,
      onDismiss = {
        signingIn = null
        if (accounts.status.value?.isSignedIn(id) == true) onAdded(id)
      },
    )
  }
}

/** Header with a back button over a scrolling, width-capped column of settings cards. */
@Composable
internal fun SettingsPage(
  title: String,
  onBack: () -> Unit,
  snackbar: SnackbarStackState? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  SettingsPageFrame(title, onBack, snackbar) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .imePadding()
          .navigationBarsPadding()
          .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s24),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = SettingsItemWidth,
        verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
        content = content,
      )
    }
  }
}

/**
 * [SettingsPage] for a page as long as a provider's hundreds of models: only the items on screen are composed.
 * Items are centred but not spaced; each caps its own width with [SettingsItemWidth] and spaces itself.
 */
@Composable
internal fun LazySettingsPage(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
  val spacing = PipkinTokens.Spacing
  val padding = WindowInsets.navigationBars
    // The keyboard already lifts the list above the navigation bar.
    .exclude(WindowInsets.ime)
    .add(WindowInsets(left = spacing.s16, top = spacing.s24, right = spacing.s16, bottom = spacing.s24))
    .asPaddingValues()
  SettingsPageFrame(title, onBack, snackbar = null) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().imePadding(),
      contentPadding = padding,
      horizontalAlignment = Alignment.CenterHorizontally,
      content = content,
    )
  }
}

/** The width of a settings page's content, capped on wide screens. */
internal val SettingsItemWidth = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth()

@Composable
private fun SettingsPageFrame(
  title: String,
  onBack: () -> Unit,
  snackbar: SnackbarStackState?,
  body: @Composable () -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    Column(modifier = Modifier.fillMaxSize()) {
      HeaderBar(
        title = title,
        leading = {
          HeaderButton(
            icon = R.drawable.ic_lucide_chevron_left,
            contentDescription = stringResource(R.string.settings_back),
            onClick = onBack,
          )
        },
      )
      body()
    }
    if (snackbar != null) {
      SnackbarStack(
        snackbar,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(PipkinTokens.Spacing.s16),
      )
    }
  }
}

@Composable
internal fun Chevron() {
  LucideIcon(icon = R.drawable.ic_lucide_chevron_right, contentDescription = null, tint = pipkinColors.foregroundMuted)
}

@Composable
private fun RowIcon(icon: Int) {
  LucideIcon(icon = icon, contentDescription = null, tint = pipkinColors.foregroundMuted)
}
