package io.jeph.pipkin.ui.settings

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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.BundledExtension
import io.jeph.pipkin.data.PiExtensionUpdate
import io.jeph.pipkin.data.PiExtensionUpdateOutcome
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.components.SnackbarStack
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.ThemeMode
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** Paseo-style settings: providers, agent, appearance, Termux, advanced and about cards under a back header. */
@Composable
internal fun SettingsScreen(
  onBack: () -> Unit,
  onOpenPermissions: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenBackups: () -> Unit,
  onOpenAdvanced: () -> Unit,
) {
  val graph = LocalContext.current.appGraph
  val snackbar = remember { SnackbarStackState() }
  Box(modifier = Modifier.fillMaxSize().background(pipkinColors.surface0)) {
    Column(modifier = Modifier.fillMaxSize()) {
      HeaderBar(
        title = stringResource(R.string.settings_title),
        leading = {
          IconButton28(
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
        onOpenPermissions = onOpenPermissions,
        onOpenBackups = onOpenBackups,
        onOpenAdvanced = onOpenAdvanced,
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
  onOpenPermissions: () -> Unit,
  onOpenBackups: () -> Unit,
  onOpenAdvanced: () -> Unit,
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
      ProvidersSection(onOpenProviders = onOpenProviders)
      AgentSection(graph = graph, snackbar = snackbar, onOpenBackups = onOpenBackups)
      ExtensionsSection(graph = graph)
      AppearanceSection(preferences = graph.preferences)
      TermuxSection(graph = graph, onOpenPermissions = onOpenPermissions)
      AdvancedSection(onOpenAdvanced = onOpenAdvanced)
      AboutSection()
    }
  }
}

/** One row into the providers page. */
@Composable
private fun ProvidersSection(onOpenProviders: () -> Unit) {
  SettingsSection(title = stringResource(R.string.settings_section_providers)) {
    SettingsRow(
      title = stringResource(R.string.providers_title),
      onClick = onOpenProviders,
      leading = {
        LucideIcon(icon = R.drawable.ic_lucide_plug, contentDescription = null, tint = pipkinColors.foregroundMuted)
      },
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_chevron_right,
        contentDescription = null,
        tint = pipkinColors.foregroundMuted,
      )
    }
  }
}

@Composable
private fun AgentSection(graph: AppGraph, snackbar: SnackbarStackState, onOpenBackups: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val reloaded = stringResource(R.string.settings_reload_agents_done)
  val configFolder = stringResource(R.string.settings_config_folder)
  val configDir = hello?.configDir?.takeIf { it.isNotEmpty() }
  SettingsSection(title = stringResource(R.string.settings_section_agent)) {
    SettingsRow(
      title = stringResource(R.string.settings_reload_agents),
      onClick = {
        graph.registry.reloadIdle()
        scope.launch { snackbar.show(reloaded) }
      },
    )
    SettingsDivider()
    UpdateExtensionsRow(update = graph.piExtensionUpdate, snackbar = snackbar)
    SettingsDivider()
    SettingsRow(
      title = configFolder,
      subtitle = configDir ?: stringResource(R.string.settings_config_folder_unknown),
    ) {
      if (configDir != null) {
        IconButton28(
          icon = R.drawable.ic_lucide_copy,
          contentDescription = stringResource(R.string.settings_copy),
          onClick = { copyToClipboard(context, configFolder, configDir) },
          tint = pipkinColors.foregroundMuted,
        )
      }
    }
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
  SettingsRow(
    title = stringResource(R.string.settings_update_extensions),
    subtitle = if (running) progress ?: stringResource(R.string.settings_update_extensions_running) else null,
    enabled = !running,
    onClick = { confirming = true },
  ) {
    if (running) SimmeringPot()
  }
  if (confirming) {
    ConfirmDialog(
      title = stringResource(R.string.settings_update_extensions_title),
      text = stringResource(R.string.settings_update_extensions_text),
      confirmLabel = stringResource(R.string.settings_update_extensions_confirm),
      onConfirm = {
        confirming = false
        update.start()
      },
      onDismiss = { confirming = false },
    )
  }
}

/**
 * A switch for each extension Pipkin bundles. A change reloads idle chats so they pick it up at once; a working chat
 * picks it up when its agent next starts.
 */
@Composable
private fun ExtensionsSection(graph: AppGraph) {
  val disabled by graph.preferences.disabledExtensions.collectAsStateWithLifecycle()
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12)) {
    SettingsSection(title = stringResource(R.string.settings_section_extensions)) {
      BundledExtension.entries.forEachIndexed { index, extension ->
        if (index > 0) SettingsDivider()
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
          Switch(checked = enabled, onCheckedChange = null)
        }
      }
    }
    SettingsCaption(text = stringResource(R.string.settings_extensions_caption))
  }
}

@Composable
private fun AppearanceSection(preferences: Preferences) {
  val theme by preferences.theme.collectAsStateWithLifecycle()
  SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
    SettingsRow(title = stringResource(R.string.settings_theme)) {
      SegmentedControl(
        options = listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.System),
        selected = theme,
        onSelect = preferences::setTheme,
      ) { mode, isSelected ->
        val colors = pipkinColors
        LucideIcon(
          icon =
            when (mode) {
              ThemeMode.Light -> R.drawable.ic_lucide_sun
              ThemeMode.Dark -> R.drawable.ic_lucide_moon
              ThemeMode.System -> R.drawable.ic_lucide_monitor
            },
          contentDescription =
            stringResource(
              when (mode) {
                ThemeMode.Light -> R.string.settings_theme_light
                ThemeMode.Dark -> R.string.settings_theme_dark
                ThemeMode.System -> R.string.settings_theme_system
              },
            ),
          tint = if (isSelected) colors.foreground else colors.foregroundMuted,
        )
      }
    }
  }
}

/** One row into the advanced page. */
@Composable
private fun AdvancedSection(onOpenAdvanced: () -> Unit) {
  SettingsSection(title = null) {
    SettingsRow(
      title = stringResource(R.string.settings_advanced),
      onClick = onOpenAdvanced,
      leading = {
        LucideIcon(icon = R.drawable.ic_lucide_wrench, contentDescription = null, tint = pipkinColors.foregroundMuted)
      },
    ) {
      LucideIcon(
        icon = R.drawable.ic_lucide_chevron_right,
        contentDescription = null,
        tint = pipkinColors.foregroundMuted,
      )
    }
  }
}

@Composable
private fun AboutSection() {
  val context = LocalContext.current
  val version = remember(context) {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
  }
  SettingsSection(title = stringResource(R.string.settings_section_about)) {
    SettingsRow(title = stringResource(R.string.settings_version)) { SettingsValue(version) }
  }
}
