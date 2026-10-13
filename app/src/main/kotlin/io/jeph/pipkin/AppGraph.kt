package io.jeph.pipkin

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.agent.TurnNotifications
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.data.ModelDefaults
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.data.SessionArchive
import io.jeph.pipkin.data.SessionRepository
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.terminal.TerminalRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app's long-lived objects, built once by [PipkinApplication]. Reach it with [Context.appGraph]. */
internal class AppGraph(application: Application) {
  /** Lives as long as the process; main-thread confined. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val hostClient = HostClient(
    application,
    foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) },
  )
  val keepAlive = KeepAlive(application)
  val preferences = Preferences(application)
  val sessionRepository = SessionRepository(hostClient, scope)
  val sessionArchive = SessionArchive(sessionRepository, preferences, scope)
  private val turnNotifications = TurnNotifications(application)
  val registry: AgentRegistry<AgentProcess> =
    AgentRegistry(scope, onRunEnded = turnNotifications::runEnded) { cwd, sessionPath ->
      AgentProcess(hostClient, keepAlive, cwd, sessionPath, scope, onSessionChanged = sessionRepository::refresh)
    }
  val accountRepository = AccountRepository(hostClient, registry, keepAlive)
  val modelDefaults = ModelDefaults(hostClient)
  val terminals = TerminalRegistry(application, hostClient, scope)

  init {
    holdWhileBusy()
    ProcessLifecycleOwner.get().lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
          // Before setup, Termux may not be able to run the host yet.
          if (preferences.setupComplete.value) sessionRepository.refresh()
          // Events may have gone missing while Pipkin was in the background.
          for (process in registry.processes.value.values) scope.launch { process.resync() }
        }
      },
    )
  }

  /** One [KeepAlive] acquisition while any chat works, so switching apps does not stop it. */
  private fun holdWhileBusy() {
    scope.launch {
      var hold: AutoCloseable? = null
      registry.busy.collect { busy ->
        if (busy && hold == null) {
          hold = keepAlive.acquire("chats working")
        } else if (!busy) {
          hold?.close()
          hold = null
        }
      }
    }
  }
}

internal val Context.appGraph: AppGraph get() = (applicationContext as PipkinApplication).graph
