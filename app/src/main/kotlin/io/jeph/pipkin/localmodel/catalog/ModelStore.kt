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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
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
  preferences: Preferences,
  private val keepAlive: KeepAlive,
) {
  private val dir = File(context.noBackupFilesDir, DIRECTORY)
  private val downloader = ModelDownloader(dir, HttpsDownloadSource)
  private val downloadedState = MutableStateFlow<Set<String>>(emptySet())
  private val scannedState = MutableStateFlow(false)
  private val hardwareState = MutableStateFlow<DeviceProfile?>(null)
  private val downloadsState = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
  private val downloadJobs = mutableMapOf<String, Job>()
  private val cleanups = mutableMapOf<String, Job>()

  /** The file names of the catalog files downloaded and verified. */
  val downloaded: StateFlow<Set<String>> = downloadedState.asStateFlow()

  /** Each model's download that is running or failed, by catalog id. */
  val downloads: StateFlow<Map<String, DownloadState>> = downloadsState.asStateFlow()

  /**
   * This phone, which decides the build of each model, with the builds that failed to load here; null until the OpenCL
   * driver has been asked for the GPU.
   */
  val device: StateFlow<DeviceProfile?> =
    combine(hardwareState, preferences.localModelFailedBuilds) { hardware, failed ->
      hardware?.copy(failedBuilds = failed)
    }.stateIn(scope, SharingStarted.Eagerly, null)

  private val scannedInstalled: StateFlow<List<InstalledModel>?> =
    combine(scannedState, downloadedState, device.filterNotNull()) { scanned, files, phone ->
      if (scanned) installedModels(LocalModelCatalog.models, files, phone, dir) else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

  /**
   * The models downloaded and verified, each with the build chosen for this phone, from the first scan of the phone's
   * files on. Before it finishes [installed] is empty, which here would wrongly mean that no model is downloaded.
   */
  val installedOnceScanned: Flow<List<InstalledModel>> = scannedInstalled.filterNotNull()

  /** [installedOnceScanned], empty until the first scan is done. */
  val installed: StateFlow<List<InstalledModel>> =
    installedOnceScanned.stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    scope.launch {
      val (hardware, files) = withContext(Dispatchers.IO) { DeviceProfile.current(context, preferences) to scan() }
      hardwareState.value = hardware
      downloadedState.value = files
      scannedState.value = true
    }
  }

  /** The build of [model] to run here, or null before the phone is known or when no build runs on it. */
  fun chosen(model: CatalogModel): ModelArtifact? = device.value?.let { EngineChoice.choose(model, it) }

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
 * Each model of [models] with a file among [downloaded] that runs on [device], with the build [EngineChoice] picks
 * among the downloaded ones, the best for the phone when it's downloaded, else one the user fetched before, and the
 * context it gives that build here. An NPU build for another phone's SoC, or a build that failed to load here, isn't
 * listed.
 */
internal fun installedModels(
  models: List<CatalogModel>,
  downloaded: Set<String>,
  device: DeviceProfile,
  dir: File,
): List<InstalledModel> = models.mapNotNull { model ->
  val local = model.copy(artifacts = model.artifacts.filter { it.fileName in downloaded })
  val artifact = EngineChoice.choose(local, device) ?: return@mapNotNull null
  InstalledModel(model, artifact, File(dir, artifact.fileName), EngineChoice.contextSize(model, artifact, device))
}
