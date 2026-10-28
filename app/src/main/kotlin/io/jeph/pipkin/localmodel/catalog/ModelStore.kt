package io.jeph.pipkin.localmodel.catalog

import android.content.Context
import android.util.Log
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.localmodel.CatalogModel
import io.jeph.pipkin.localmodel.InstalledModel
import io.jeph.pipkin.localmodel.ModelArtifact
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a model's download stands while it runs or after it failed; a model with neither has none. */
internal sealed interface DownloadState {
  val artifact: ModelArtifact

  data class Running(override val artifact: ModelArtifact, val downloadedBytes: Long) : DownloadState

  data class Failed(override val artifact: ModelArtifact, val failure: DownloadFailure) : DownloadState
}

/**
 * The catalog's files on this phone: which are downloaded, downloading them, and which build of each model to run.
 * Files live in `noBackupFilesDir/local-models/`, out of Android's backups, since they're gigabytes anyone can
 * download again. Downloads run in the app's [scope] rather than a screen's, holding [keepAlive] so they go on while
 * Pipkin is in the background. Methods are called on the main thread.
 */
internal class ModelStore(
  context: Context,
  private val scope: CoroutineScope,
  private val preferences: Preferences,
  private val keepAlive: KeepAlive,
  /** This phone, which decides the build of each model. */
  val device: DeviceProfile = DeviceProfile.current(),
) {
  private val dir = File(context.noBackupFilesDir, DIRECTORY)
  private val downloader = ModelDownloader(dir, HttpsDownloadSource)
  private val downloadedState = MutableStateFlow<Set<String>>(emptySet())
  private val downloadsState = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
  private val downloadJobs = mutableMapOf<String, Job>()
  private val cleanups = mutableMapOf<String, Job>()

  /** The file names of the catalog files downloaded and verified. */
  val downloaded: StateFlow<Set<String>> = downloadedState.asStateFlow()

  /** Each model's download that is running or failed, by catalog id. */
  val downloads: StateFlow<Map<String, DownloadState>> = downloadsState.asStateFlow()

  /** The models downloaded and verified, each with the build chosen for this phone. */
  val installed: StateFlow<List<InstalledModel>> =
    combine(downloadedState, preferences.localModelEngines) { files, overrides ->
      installedModels(LocalModelCatalog.models, files, overrides, device, dir)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    scope.launch { downloadedState.value = withContext(Dispatchers.IO) { scan() } }
  }

  /** The build of [model] to run here, following the engine the user picked for it. */
  fun chosen(model: CatalogModel): ModelArtifact? =
    EngineChoice.choose(model, device, preferences.localModelEngines.value[model.id] ?: EngineOverride.AUTOMATIC)

  /** Downloads the build of [model] [chosen] now, unless it's downloading already or has no build for this phone. */
  fun download(model: CatalogModel) {
    if (downloadJobs[model.id]?.isActive == true) return
    val artifact = chosen(model) ?: return
    val cleanup = cleanups[model.id]
    downloadsState.update { it + (model.id to DownloadState.Running(artifact, 0)) }
    val job = scope.launch(start = CoroutineStart.LAZY) {
      val self = coroutineContext.job
      val current = { downloadJobs[model.id] === self }
      try {
        cleanup?.join()
        keepAlive.acquire("model download").use {
          withContext(Dispatchers.IO) {
            downloader.download(artifact) { bytes ->
              // Published on the main thread, where cancel and delete run, so progress that lands after either
              // can't bring the row back.
              scope.launch {
                if (current()) downloadsState.update { it + (model.id to DownloadState.Running(artifact, bytes)) }
              }
            }
          }
        }
        downloadedState.update { it + artifact.fileName }
        downloadsState.update { it - model.id }
      } catch (e: DownloadException) {
        Log.w(TAG, "Downloading ${artifact.fileName} failed: ${e.failure}", e)
        if (current()) downloadsState.update { it + (model.id to DownloadState.Failed(artifact, e.failure)) }
      } finally {
        if (current()) downloadJobs.remove(model.id)
      }
    }
    downloadJobs[model.id] = job
    job.start()
  }

  /** Stops [model]'s download, or forgets that it failed, and deletes what it got so far. */
  fun cancel(model: CatalogModel) = remove(model, downloaded = false)

  /** Deletes every downloaded file and part of [model]; the caller unloads it first if it's running. */
  fun delete(model: CatalogModel) = remove(model, downloaded = true)

  /**
   * Stops [model]'s download and deletes its parts, and its [downloaded] files too, once the download has stopped
   * writing. A download started meanwhile waits for the deleting to finish, so it never writes into a part that's
   * about to go.
   */
  private fun remove(model: CatalogModel, downloaded: Boolean) {
    val download = downloadJobs.remove(model.id)
    download?.cancel()
    downloadsState.update { it - model.id }
    if (downloaded) downloadedState.update { files -> files - model.artifacts.map { it.fileName }.toSet() }
    val previous = cleanups[model.id]
    val job = scope.launch(start = CoroutineStart.LAZY) {
      download?.join()
      previous?.join()
      withContext(Dispatchers.IO) {
        for (artifact in model.artifacts) {
          downloader.partFile(artifact).delete()
          if (downloaded) downloader.file(artifact).delete()
        }
      }
      if (cleanups[model.id] === coroutineContext.job) cleanups.remove(model.id)
    }
    cleanups[model.id] = job
    job.start()
  }

  /**
   * The catalog files already downloaded, by the size they must have; a part stays for resuming. Other files are left
   * alone: only Delete removes anything.
   */
  private fun scan(): Set<String> = LocalModelCatalog.models.flatMap { it.artifacts }
    .filter { downloader.file(it).length() == it.sizeBytes }
    .map { it.fileName }
    .toSet()

  private companion object {
    const val DIRECTORY = "local-models"
    const val TAG = "Pipkin"
  }
}

/**
 * Each model of [models] with a file among [downloaded] that runs on [device]: the build [EngineChoice] picks under the
 * user's [overrides] when that one is downloaded, else the best one downloaded, since the user fetched it on purpose.
 * An NPU build for another phone's SoC isn't listed, since it can't load here.
 */
internal fun installedModels(
  models: List<CatalogModel>,
  downloaded: Set<String>,
  overrides: Map<String, EngineOverride>,
  device: DeviceProfile,
  dir: File,
): List<InstalledModel> = models.mapNotNull { model ->
  val chosen = EngineChoice.choose(model, device, overrides[model.id] ?: EngineOverride.AUTOMATIC)
  val artifact = chosen?.takeIf { it.fileName in downloaded }
    ?: EngineChoice.runnable(model, device).firstOrNull { it.fileName in downloaded }
  artifact?.let { InstalledModel(model, it, File(dir, it.fileName)) }
}
