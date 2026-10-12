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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.ThemeMode
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.launch

/** Paseo-style settings: providers, agent, appearance, Termux and about cards under a back header. */
@Composable
internal fun SettingsScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenProviders: () -> Unit) {
  val graph = LocalContext.current.appGraph
  val snackbar = remember { SnackbarHostState() }
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
      )
    }
    SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
  }
}

@Composable
private fun SettingsContent(
  graph: AppGraph,
  snackbar: SnackbarHostState,
  onOpenProviders: () -> Unit,
  onOpenPermissions: () -> Unit,
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
      AgentSection(graph = graph, snackbar = snackbar)
      AppearanceSection(preferences = graph.preferences)
      TermuxSection(graph = graph, onOpenPermissions = onOpenPermissions)
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
private fun AgentSection(graph: AppGraph, snackbar: SnackbarHostState) {
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
        scope.launch { snackbar.showSnackbar(reloaded) }
      },
    )
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
