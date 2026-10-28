package io.jeph.pipkin.ui.settings

import android.content.Context
import android.icu.text.NumberFormat
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.localmodel.CatalogModel
import io.jeph.pipkin.localmodel.LocalModelRuntime
import io.jeph.pipkin.localmodel.LocalModels
import io.jeph.pipkin.localmodel.ModelArtifact
import io.jeph.pipkin.localmodel.RuntimeState
import io.jeph.pipkin.localmodel.catalog.DeviceProfile
import io.jeph.pipkin.localmodel.catalog.DownloadFailure
import io.jeph.pipkin.localmodel.catalog.DownloadState
import io.jeph.pipkin.localmodel.catalog.EngineChoice
import io.jeph.pipkin.localmodel.catalog.EngineOverride
import io.jeph.pipkin.localmodel.catalog.LocalModelCatalog
import io.jeph.pipkin.localmodel.catalog.LocalModelSettings
import io.jeph.pipkin.localmodel.engine.Backend
import io.jeph.pipkin.localmodel.engine.EngineKind
import io.jeph.pipkin.ui.chat.ConfirmDialog
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Settings › Experimental: one row into [LocalModelsScreen], saying whether local models are on. */
@Composable
internal fun ExperimentalSection(preferences: Preferences, onOpenLocalModels: () -> Unit) {
  val enabled by preferences.localModelsEnabled.collectAsStateWithLifecycle()
  SettingsSection(title = stringResource(R.string.settings_section_experimental)) {
    SettingsRow(
      title = stringResource(R.string.local_models_title),
      subtitle = stringResource(if (enabled) R.string.local_models_row_on else R.string.local_models_row_off),
      onClick = onOpenLocalModels,
      leading = {
        LucideIcon(icon = R.drawable.ic_lucide_brain, contentDescription = null, tint = pipkinColors.foregroundMuted)
      },
    ) {
      Chevron()
    }
  }
}

/**
 * Turning local models on, what the model process is doing, and downloading the catalog's models, each in the build
 * chosen for this phone unless the user picks the engine.
 */
@Composable
internal fun LocalModelsScreen(onBack: () -> Unit) {
  val graph = LocalContext.current.appGraph
  val preferences = graph.preferences
  val enabled by preferences.localModelsEnabled.collectAsStateWithLifecycle()
  SettingsPage(title = stringResource(R.string.local_models_title), onBack = onBack) {
    SettingsSection(title = null) {
      SettingsRow(
        title = stringResource(R.string.local_models_enable),
        subtitle = stringResource(R.string.local_models_enable_summary),
        modifier = Modifier.toggleable(
          value = enabled,
          role = Role.Switch,
          onValueChange = preferences::setLocalModelsEnabled,
        ),
      ) {
        SettingsSwitch(checked = enabled)
      }
    }
    if (enabled) {
      StatusSection(graph.localModels.runtime, preferences)
      SettingsCaption(text = stringResource(R.string.local_models_status_caption))
      SettingsSection(title = stringResource(R.string.local_models_section_models)) {
        LocalModelCatalog.models.forEachIndexed { index, model ->
          if (index > 0) SettingsDivider()
          ModelEntry(model, graph.localModels, preferences)
        }
      }
    }
  }
}

/** What the model process is doing, with Unload while it holds a model, and how long an idle model stays loaded. */
@Composable
private fun StatusSection(runtime: LocalModelRuntime, preferences: Preferences) {
  val state by runtime.state.collectAsStateWithLifecycle()
  val idleMinutes by preferences.localModelIdleUnloadMinutes.collectAsStateWithLifecycle()
  SettingsSection(title = stringResource(R.string.local_models_section_status)) {
    SettingsRow(title = statusTitle(state), subtitle = statusSummary(state)) {
      if (state != RuntimeState.Stopped) {
        SmallButton(label = stringResource(R.string.local_models_unload), onClick = runtime::unload)
      }
    }
    SettingsDivider()
    SettingsRow(title = stringResource(R.string.local_models_idle_unload)) {
      Stepper(
        value = idleMinutes,
        range = LocalModelSettings.IDLE_UNLOAD_MINUTES,
        onChange = preferences::setLocalModelIdleUnloadMinutes,
        fewer = stringResource(R.string.local_models_idle_unload_fewer),
        more = stringResource(R.string.local_models_idle_unload_more),
      )
    }
  }
}

@Composable
private fun statusTitle(state: RuntimeState): String = when (state) {
  RuntimeState.Stopped -> stringResource(R.string.local_models_stopped)
  is RuntimeState.Loading -> stringResource(R.string.local_models_loading, LocalModelCatalog.name(state.modelId))
  is RuntimeState.Ready -> stringResource(R.string.local_models_ready, LocalModelCatalog.name(state.modelId))
  is RuntimeState.Failed -> stringResource(R.string.local_models_failed, LocalModelCatalog.name(state.modelId))
}

@Composable
private fun statusSummary(state: RuntimeState): String {
  val locale = LocalConfiguration.current.locales[0]
  return when (state) {
    RuntimeState.Stopped -> stringResource(R.string.local_models_stopped_summary)

    is RuntimeState.Loading -> stringResource(
      R.string.local_models_loading_summary,
      NumberFormat.getPercentInstance(locale).format(state.progress),
    )

    is RuntimeState.Ready -> stringResource(
      R.string.local_models_ready_summary,
      stringResource(state.engine.label),
      stringResource(state.backend.label),
      NumberFormat.getIntegerInstance(locale).format(state.contextSize),
    )

    is RuntimeState.Failed -> state.message
  }
}

/**
 * One catalog model: the build chosen for this phone and its size, what's downloaded, the download's progress or why
 * it failed, and its actions; then the engine, when the model has builds for more than one that run here.
 */
@Composable
private fun ModelEntry(model: CatalogModel, localModels: LocalModels, preferences: Preferences) {
  val context = LocalContext.current
  val store = localModels.store
  val downloads by store.downloads.collectAsStateWithLifecycle()
  val downloaded by store.downloaded.collectAsStateWithLifecycle()
  val overrides by preferences.localModelEngines.collectAsStateWithLifecycle()
  val override = overrides[model.id] ?: EngineOverride.AUTOMATIC
  val chosen = EngineChoice.choose(model, store.device, override)
  val download = downloads[model.id]
  val files = model.artifacts.filter { it.fileName in downloaded }
  var confirmingDelete by rememberSaveable { mutableStateOf(false) }
  val subtitle = when {
    chosen == null -> stringResource(R.string.local_models_unavailable)

    chosen.fileName in downloaded -> stringResource(R.string.local_models_downloaded, buildLabel(context, chosen))

    else -> listOfNotNull(
      buildLabel(context, chosen),
      files.firstOrNull()?.let { stringResource(R.string.local_models_downloaded, buildLabel(context, it)) },
    ).joinToString("\n")
  }
  SettingsRow(title = model.name, subtitle = subtitle) {
    // Stacked, so the build's description keeps its width when Download and Delete both apply.
    Column(
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    ) {
      ModelActions(
        running = download is DownloadState.Running,
        failed = download is DownloadState.Failed,
        canDownload = chosen != null && chosen.fileName !in downloaded,
        hasFiles = files.isNotEmpty(),
        onDownload = { store.download(model) },
        onCancel = { store.cancel(model) },
        onDelete = { confirmingDelete = true },
      )
    }
  }
  when (download) {
    is DownloadState.Running -> DownloadProgress(download)

    is DownloadState.Failed -> SettingsCaption(
      text = failureText(context, download.failure),
      modifier = Modifier.padding(
        start = PipkinTokens.Spacing.s12,
        end = PipkinTokens.Spacing.s12,
        bottom = PipkinTokens.Spacing.s12,
      ),
      color = pipkinColors.destructive,
    )

    null -> Unit
  }
  EngineControl(model, store.device, override) { preferences.setLocalModelEngine(model.id, it) }
  if (confirmingDelete) {
    DeleteDialog(model, files.sumOf { it.sizeBytes }, localModels) { confirmingDelete = false }
  }
}

/** Confirms deleting [model]'s [bytes] of files, unloading it first when it's the model running. */
@Composable
private fun DeleteDialog(model: CatalogModel, bytes: Long, localModels: LocalModels, onClose: () -> Unit) {
  val context = LocalContext.current
  ConfirmDialog(
    title = stringResource(R.string.local_models_delete_title, model.name),
    text = stringResource(R.string.local_models_delete_text, Formatter.formatShortFileSize(context, bytes)),
    confirmLabel = stringResource(R.string.local_models_delete),
    icon = R.drawable.ic_lucide_trash_2,
    destructive = true,
    onConfirm = {
      onClose()
      if (localModels.runtime.state.value.modelId == model.id) localModels.runtime.unload()
      localModels.store.delete(model)
    },
    onDismiss = onClose,
  )
}

/** Cancel while downloading; otherwise Download (Try again after a failure) and Delete, as each applies. */
@Composable
private fun ModelActions(
  running: Boolean,
  failed: Boolean,
  canDownload: Boolean,
  hasFiles: Boolean,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
) {
  if (running) {
    SmallButton(label = stringResource(R.string.local_models_cancel), onClick = onCancel)
    return
  }
  if (canDownload) {
    val label = if (failed) R.string.local_models_retry else R.string.local_models_download
    SmallButton(label = stringResource(label), onClick = onDownload)
  }
  if (hasFiles) SmallButton(label = stringResource(R.string.local_models_delete), onClick = onDelete)
}

@Composable
private fun DownloadProgress(download: DownloadState.Running) {
  val context = LocalContext.current
  val total = download.artifact.sizeBytes
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s12),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
  ) {
    LinearProgressIndicator(
      progress = { download.downloadedBytes.toFloat() / total },
      modifier = Modifier.fillMaxWidth(),
      color = pipkinColors.accent,
      trackColor = pipkinColors.surface3,
      drawStopIndicator = {},
    )
    Text(
      text = stringResource(
        R.string.local_models_progress,
        Formatter.formatShortFileSize(context, download.downloadedBytes),
        Formatter.formatShortFileSize(context, total),
      ),
      color = pipkinColors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
    )
  }
}

/** Automatic or one of the engines [model] has a build of that runs on [device]; hidden when there's no choice. */
@Composable
private fun EngineControl(
  model: CatalogModel,
  device: DeviceProfile,
  override: EngineOverride,
  onSelect: (EngineOverride) -> Unit,
) {
  val engines = EngineChoice.engines(model, device)
  if (engines.size < 2) return
  val colors = pipkinColors
  val options = listOf(EngineOverride.AUTOMATIC) + EngineOverride.entries.filter { it.engine in engines }
  // The control moves under its label, and scrolls, when longer translations don't fit beside it.
  FlowRow(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = PipkinTokens.Spacing.s16, end = PipkinTokens.Spacing.s16, bottom = PipkinTokens.Spacing.s12),
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.local_models_engine),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
    )
    SegmentedControl(
      options = options,
      selected = override,
      onSelect = onSelect,
      modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) { option, isSelected ->
      Text(
        text = stringResource(option.label),
        color = if (isSelected) colors.foreground else colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.meta,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}
