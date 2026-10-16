package io.jeph.pipkin.data

import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.Backups
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A backup or restore under way, with the latest line of its progress. */
internal sealed interface BackupTask {
  val progress: String?

  data class Creating(override val progress: String? = null) : BackupTask

  data class Restoring(val name: String, override val progress: String? = null) : BackupTask
}

/** How the last backup or restore ended; [Failed.message] is null when the host gave no reason. */
internal sealed interface BackupOutcome {
  data object Created : BackupOutcome

  data object Restored : BackupOutcome

  data class Failed(val message: String?) : BackupOutcome
}

/**
 * Backups of Pi's configuration, kept in Termux. Backups and restores run in [scope], so leaving the screen that
 * started one neither cancels it nor loses its outcome; one runs at a time. A restore replaces the configuration but
 * never the sessions, so every chat's Pi is stopped first and the chats on screen start again afterwards with the
 * restored settings. Main thread only.
 */
internal class ConfigBackups(
  private val hostClient: HostClient,
  private val registry: AgentRegistry<*>,
  private val keepAlive: KeepAlive,
  private val accounts: AccountRepository,
  private val sessions: SessionRepository,
  private val scope: CoroutineScope,
) {
  private val taskState = MutableStateFlow<BackupTask?>(null)
  private val outcomeState = MutableStateFlow<BackupOutcome?>(null)

  /** The backup or restore under way, if any. */
  val task: StateFlow<BackupTask?> = taskState.asStateFlow()

  val outcome: StateFlow<BackupOutcome?> = outcomeState.asStateFlow()

  /** Throws [HostFailure][io.jeph.pipkin.host.HostFailure] or [HostRejected]. */
  suspend fun list(): Backups = hostClient.backups()

  /** Throws [HostFailure][io.jeph.pipkin.host.HostFailure] or [HostRejected]. */
  suspend fun delete(name: String) = hostClient.deleteBackup(name)

  fun create() = run(BackupTask.Creating()) {
    keepAlive.acquire("backing up").use { hostClient.createBackup(::progress) }
    BackupOutcome.Created
  }

  fun restore(name: String) = run(BackupTask.Restoring(name)) {
    keepAlive.acquire("restoring").use {
      registry.stopAll()
      try {
        hostClient.restoreBackup(name, ::progress)
      } finally {
        // Also after a failed restore, which leaves the configuration as it was, so the chats work again.
        withContext(NonCancellable) { registry.restartShown() }
      }
    }
    accounts.refresh()
    sessions.refresh()
    BackupOutcome.Restored
  }

  private fun progress(line: String) {
    taskState.update { task ->
      when (task) {
        is BackupTask.Creating -> task.copy(progress = line)
        is BackupTask.Restoring -> task.copy(progress = line)
        null -> null
      }
    }
  }

  private fun run(task: BackupTask, work: suspend () -> BackupOutcome) {
    if (taskState.value != null) return
    taskState.value = task
    outcomeState.value = null
    scope.launch {
      outcomeState.value = try {
        work()
      } catch (failure: IOException) {
        BackupOutcome.Failed(failure.message)
      } catch (rejected: HostRejected) {
        BackupOutcome.Failed(rejected.error)
      } finally {
        taskState.value = null
      }
    }
  }
}
