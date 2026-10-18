package io.jeph.pipkin.data

import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the last update of Pi's extensions ended; [Failed.message] is null when the host gave no reason. */
internal sealed interface PiExtensionUpdateOutcome {
  data object Updated : PiExtensionUpdateOutcome

  data class Failed(val message: String?) : PiExtensionUpdateOutcome
}

/**
 * Updates the Pi packages the user installed, through Pi's `pi update --extensions`. The update runs in [scope], so
 * leaving the screen that started it neither cancels it nor loses its outcome; one runs at a time. Afterwards idle
 * chats reload so they load the new versions, and working chats load them when their agent next starts. They reload
 * after a failed update too, since Pi may have updated some packages before it stopped, and chats that couldn't start
 * during the update start then. Main thread only.
 */
internal class PiExtensionUpdate(
  private val hostClient: HostClient,
  private val registry: AgentRegistry<*>,
  private val keepAlive: KeepAlive,
  private val sessions: SessionRepository,
  private val scope: CoroutineScope,
) {
  private val runningState = MutableStateFlow(false)
  private val progressState = MutableStateFlow<String?>(null)
  private val outcomeState = MutableStateFlow<PiExtensionUpdateOutcome?>(null)

  val running: StateFlow<Boolean> = runningState.asStateFlow()

  /** The latest line of Pi's output while [running]. */
  val progress: StateFlow<String?> = progressState.asStateFlow()

  /** How the last update ended, until [acknowledge]. */
  val outcome: StateFlow<PiExtensionUpdateOutcome?> = outcomeState.asStateFlow()

  fun start() {
    if (runningState.value) return
    runningState.value = true
    progressState.value = null
    outcomeState.value = null
    scope.launch {
      var updateRan = true
      val outcome = try {
        keepAlive.acquire("updating extensions").use { hostClient.updateExtensions { progressState.value = it } }
        PiExtensionUpdateOutcome.Updated
      } catch (failure: IOException) {
        PiExtensionUpdateOutcome.Failed(failure.message)
      } catch (rejected: HostRejected) {
        // Busy: a backup or restore holds the configuration, so nothing changed and chats can't start until it's done.
        updateRan = rejected.code != CONFIG_BUSY
        PiExtensionUpdateOutcome.Failed(rejected.error)
      } finally {
        runningState.value = false
        progressState.value = null
      }
      if (updateRan) {
        // An updated extension may no longer set the titles, statuses and widgets its old version left behind.
        sessions.forgetExtensionUi()
        registry.reloadIdle()
      }
      outcomeState.value = outcome
    }
  }

  /** Forgets [outcome] once it has been shown. */
  fun acknowledge() {
    outcomeState.value = null
  }

  private companion object {
    /** The host's refusal while another backup, restore or update holds the config lock. */
    const val CONFIG_BUSY = "busy"
  }
}
