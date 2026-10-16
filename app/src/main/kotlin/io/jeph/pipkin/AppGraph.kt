package io.jeph.pipkin

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.AgentService
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.agent.TurnNotifications
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.data.ConfigBackups
import io.jeph.pipkin.data.ModelDefaults
import io.jeph.pipkin.data.NoticeStore
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.data.SessionArchive
import io.jeph.pipkin.data.SessionRepository
import io.jeph.pipkin.files.FileTabRegistry
import io.jeph.pipkin.git.CommitTabRegistry
import io.jeph.pipkin.host.FileHost
import io.jeph.pipkin.host.GitHost
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.terminal.TerminalRegistry
import io.jeph.pipkin.workspace.OpenTabs
import io.jeph.pipkin.workspace.Panes
import io.jeph.pipkin.workspace.WorkspaceLayout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app's long-lived objects, built once by [PipkinApplication]. Reach it with [Context.appGraph]. */
internal class AppGraph(private val application: Application) {
  /** Lives as long as the process; main-thread confined. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val hostClient = HostClient(
    application,
    foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) },
  )
  val keepAlive = KeepAlive(application)
  val preferences = Preferences(application)
  val sessionRepository = SessionRepository(hostClient, NoticeStore(File(application.filesDir, "notices")), scope)
  val sessionArchive = SessionArchive(sessionRepository, preferences, scope)
  private val turnNotifications = TurnNotifications(application) { hostClient.lastHello.value?.home }
  val registry: AgentRegistry<AgentProcess> =
    AgentRegistry(
      scope,
      maxRunning = preferences.maxRunningChats,
      idleParkMillis = preferences.idleStop.map { it.millis },
      onRunEnded = turnNotifications::runEnded,
    ) { cwd, sessionPath ->
      AgentProcess(hostClient, keepAlive, cwd, sessionPath, scope, sessionRepository, preferences.disabledExtensions)
    }
  val accountRepository = AccountRepository(hostClient, registry, keepAlive)
  val configBackups = ConfigBackups(hostClient, registry, keepAlive, accountRepository, sessionRepository, scope)
  val modelDefaults = ModelDefaults(hostClient)
  val panes = Panes(registry, sessionArchive::isArchived)
  val tabs = OpenTabs(panes)
  val terminals = TerminalRegistry(application, hostClient, scope, tabs)
  val fileHost = FileHost(hostClient, scope)
  val files = FileTabRegistry(fileHost, tabs)
  val gitHost = GitHost(hostClient, scope)
  val commits = CommitTabRegistry(gitHost, tabs, preferences.trustedRepositories, scope)
  val layout = WorkspaceLayout(this)

  init {
    holdWhileWorking()
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

  /**
   * One [KeepAlive] acquisition while any chat works or any terminal runs a command, so switching apps does not stop
   * them. The notification follows which of the two is working, also while sign-in or a Pi install keeps it up.
   */
  private fun holdWhileWorking() {
    scope.launch {
      var hold: AutoCloseable? = null
      registry.busy.combine(terminals.busy, ::Pair).collect { (chats, terminalsBusy) ->
        if (chats || terminalsBusy) {
          if (hold == null) hold = keepAlive.acquire(if (chats) "chats working" else "terminal command")
        } else {
          hold?.close()
          hold = null
        }
        // The service may still run for another holder; a no-op once it has stopped.
        AgentService.refresh(application)
      }
    }
  }
}

internal val Context.appGraph: AppGraph get() = (applicationContext as PipkinApplication).graph
