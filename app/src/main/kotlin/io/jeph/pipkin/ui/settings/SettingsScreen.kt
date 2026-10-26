package io.jeph.pipkin.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.icu.text.ListFormatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.data.BundledExtension
import io.jeph.pipkin.data.PiExtensionUpdate
import io.jeph.pipkin.data.PiExtensionUpdateOutcome
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.HeaderButton
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** Settings: models, chats, appearance, extensions, agent, configuration and about cards under a header. */
@Composable
internal fun SettingsScreen(
  onBack: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenRunningAgents: () -> Unit,
  onOpenPermissions: () -> Unit,
  onOpenBackups: () -> Unit,
  onOpenLicenses: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val snackbar = remember { SnackbarStackState() }
  Box(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    Column(modifier = Modifier.fillMaxSize()) {
      HeaderBar(
        title = stringResource(R.string.settings_title),
        leading = {
          HeaderButton(
            icon = R.drawable.ic_lucide_chevron_left,
            contentDescription = stringResource(R.string.settings_back),
            onClick = onBack,
          )
        },
      )
      SettingsContent(
        graph = graph,
        snackbar = snackbar,
        onOpenProviders = onOpenProviders,
        onOpenRunningAgents = onOpenRunningAgents,
        onOpenPermissions = onOpenPermissions,
        onOpenBackups = onOpenBackups,
        onOpenLicenses = onOpenLicenses,
      )
    }
    SnackbarStack(
      snackbar,
      Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(PipkinTokens.Spacing.s16),
    )
  }
}

@Composable
private fun SettingsContent(
  graph: AppGraph,
  snackbar: SnackbarStackState,
  onOpenProviders: () -> Unit,
  onOpenRunningAgents: () -> Unit,
  onOpenPermissions: () -> Unit,
  onOpenBackups: () -> Unit,
  onOpenLicenses: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s24),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s24),
    ) {
      ModelsSection(accounts = graph.accountRepository, onOpenProviders = onOpenProviders)
      ChatsSection(preferences = graph.preferences)
      AppearanceSection(preferences = graph.preferences)
      ExtensionsSection(graph = graph, snackbar = snackbar)
      AgentSection(graph = graph, onOpenRunningAgents = onOpenRunningAgents, onOpenPermissions = onOpenPermissions)
      ConfigurationSection(graph = graph, snackbar = snackbar, onOpenBackups = onOpenBackups)
      AboutSection(onOpenLicenses = onOpenLicenses)
    }
  }
}

/**
 * One row into the providers page, naming the providers added so far. It shows what the last refresh found and asks
 * for none itself, since that would start the host just for opening Settings.
 */
@Composable
private fun ModelsSection(accounts: AccountRepository, onOpenProviders: () -> Unit) {
  val status by accounts.status.collectAsStateWithLifecycle()
  val locale = LocalConfiguration.current.locales[0]
  val added = status?.let { current ->
    subscriptions.filter { current.isSignedIn(it.id) }.map { stringResource(it.title) } + current.custom.map { it.id }
  }
  val summary = when {
    added == null -> null
    added.isEmpty() -> stringResource(R.string.settings_providers_none)
    else -> ListFormatter.getInstance(locale).format(added)
  }
  SettingsSection(title = stringResource(R.string.settings_section_models)) {
    SettingsRow(
      title = stringResource(R.string.providers_title),
      subtitle = summary,
      onClick = onOpenProviders,
      leading = {
        LucideIcon(icon = R.drawable.ic_lucide_plug, contentDescription = null, tint = pipkinColors.foregroundMuted)
      },
    ) {
      Chevron()
    }
  }
}

@Composable
private fun AppearanceSection(preferences: Preferences) {
  val theme by preferences.theme.collectAsStateWithLifecycle()
  SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
    ThemePicker(selected = theme, onSelect = preferences::setTheme)
  }
}

/**
 * A switch for each extension Pipkin bundles, and updating the Pi extensions installed in its settings. A switch
 * reloads idle chats so they pick the change up at once; a working chat picks it up when its agent next starts.
 */
@Composable
private fun ExtensionsSection(graph: AppGraph, snackbar: SnackbarStackState) {
  val disabled by graph.preferences.disabledExtensions.collectAsStateWithLifecycle()
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12)) {
    SettingsSection(title = stringResource(R.string.settings_section_extensions)) {
      BundledExtension.entries.forEach { extension ->
        val enabled = extension.id !in disabled
        val toggle = { on: Boolean ->
          graph.preferences.setExtensionEnabled(extension, on)
          graph.sessionRepository.forgetExtensionUi()
          graph.registry.reloadIdle()
        }
        SettingsRow(
          title = stringResource(extension.title),
          subtitle = stringResource(extension.summary),
          modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = toggle),
        ) {
          SettingsSwitch(checked = enabled)
        }
        SettingsDivider()
      }
      UpdateExtensionsRow(update = graph.piExtensionUpdate, snackbar = snackbar)
    }
    SettingsCaption(text = stringResource(R.string.settings_extensions_caption))
  }
}

/**
 * Runs `pi update --extensions` once confirmed, showing Pi's latest output while it runs and how it ended in a
 * snackbar, including when it ended while settings were closed.
 */
@Composable
private fun UpdateExtensionsRow(update: PiExtensionUpdate, snackbar: SnackbarStackState) {
  val scope = rememberCoroutineScope()
  val running by update.running.collectAsStateWithLifecycle()
  val progress by update.progress.collectAsStateWithLifecycle()
  val outcome by update.outcome.collectAsStateWithLifecycle()
  var confirming by rememberSaveable { mutableStateOf(false) }
  val updated = stringResource(R.string.settings_update_extensions_done)
  val failed = stringResource(R.string.provider_failed)
  LaunchedEffect(outcome) {
    val ended = outcome ?: return@LaunchedEffect
    val message = when (ended) {
      PiExtensionUpdateOutcome.Updated -> updated
      is PiExtensionUpdateOutcome.Failed -> ended.message ?: failed
    }
    // Acknowledging changes this effect's key and cancels it, so the snackbar shows from the row's own scope.
    scope.launch { snackbar.show(message) }
    update.acknowledge()
  }
  val subtitle = when {
    !running -> stringResource(R.string.settings_update_extensions_summary)
    else -> progress ?: stringResource(R.string.settings_update_extensions_running)
  }
  SettingsRow(
    title = stringResource(R.string.settings_update_extensions),
    subtitle = subtitle,
    enabled = !running,
    onClick = { confirming = true },
  ) {
    if (running) SimmeringPot()
  }
  if (!confirming) return
  ConfirmDialog(
    title = stringResource(R.string.settings_update_extensions_title),
    text = stringResource(R.string.settings_update_extensions_text),
    confirmLabel = stringResource(R.string.settings_update_extensions_confirm),
    icon = R.drawable.ic_lucide_refresh_cw,
    onConfirm = {
      confirming = false
      update.start()
    },
    onDismiss = { confirming = false },
  )
}

/** Pi's config folder: where it is, reloading idle chats after editing it, and backing it up. */
@Composable
private fun ConfigurationSection(graph: AppGraph, snackbar: SnackbarStackState, onOpenBackups: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val reloaded = stringResource(R.string.settings_reload_agents_done)
  val configFolder = stringResource(R.string.settings_config_folder)
  val configDir = hello?.configDir?.takeIf { it.isNotEmpty() }
  SettingsSection(title = stringResource(R.string.settings_section_configuration)) {
    SettingsRow(
      title = configFolder,
      subtitle = configDir ?: stringResource(R.string.settings_config_folder_unknown),
    ) {
      if (configDir != null) {
        RoundIconButton(
          icon = R.drawable.ic_lucide_copy,
          contentDescription = stringResource(R.string.settings_copy),
          onClick = { copyToClipboard(context, configFolder, configDir) },
          tint = pipkinColors.foregroundMuted,
        )
      }
    }
    SettingsDivider()
    SettingsRow(
      title = stringResource(R.string.settings_reload_agents),
      subtitle = stringResource(R.string.settings_reload_agents_summary),
      onClick = {
        graph.registry.reloadIdle()
        scope.launch { snackbar.show(reloaded) }
      },
    )
    SettingsDivider()
    SettingsRow(
      title = stringResource(R.string.backups_title),
      onClick = onOpenBackups,
      leading = {
        LucideIcon(
          icon = R.drawable.ic_lucide_archive_restore,
          contentDescription = null,
          tint = pipkinColors.foregroundMuted,
        )
      },
    ) {
      Chevron()
    }
  }
}

/** The app's version, a row into the open-source licences it ships, and a link to the privacy policy on the website. */
@Composable
private fun AboutSection(onOpenLicenses: () -> Unit) {
  val context = LocalContext.current
  val version = remember(context) {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
  }
  SettingsSection(title = stringResource(R.string.settings_section_about)) {
    SettingsRow(title = stringResource(R.string.settings_version)) { SettingsValue(version) }
    SettingsDivider()
    SettingsRow(title = stringResource(R.string.settings_licenses), onClick = onOpenLicenses) {
      Chevron()
    }
    SettingsDivider()
    SettingsRow(title = stringResource(R.string.settings_privacy_policy), onClick = { openPrivacyPolicy(context) }) {
      LucideIcon(
        icon = R.drawable.ic_lucide_external_link,
        contentDescription = null,
        tint = pipkinColors.foregroundMuted,
      )
    }
  }
}

private const val PRIVACY_POLICY_URL = "https://pipkin.jeph.io/docs/privacy/"

private fun openPrivacyPolicy(context: Context) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
  }
}
