package io.jeph.pipkin

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.AgentService
import io.jeph.pipkin.agent.DialogNotifications
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.agent.TurnNotifications
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.data.ConfigBackups
import io.jeph.pipkin.data.ModelDefaults
import io.jeph.pipkin.data.NoticeStore
import io.jeph.pipkin.data.PiExtensionUpdate
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.data.SessionArchive
import io.jeph.pipkin.data.SessionRepository
import io.jeph.pipkin.files.FileTabRegistry
import io.jeph.pipkin.git.CommitTabRegistry
import io.jeph.pipkin.host.FileHost
import io.jeph.pipkin.host.GitHost
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.terminal.TerminalRegistry
import io.jeph.pipkin.ui.chat.ChatViews
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
  val dialogNotifications = DialogNotifications(application, registry, scope) { hostClient.lastHello.value?.home }
  val accountRepository = AccountRepository(hostClient, registry, keepAlive)
  val configBackups = ConfigBackups(hostClient, registry, keepAlive, accountRepository, sessionRepository, scope)
  val piExtensionUpdate = PiExtensionUpdate(hostClient, registry, keepAlive, sessionRepository, scope)
  val modelDefaults = ModelDefaults(hostClient)
  val panes = Panes(registry, sessionArchive::isArchived)
  val tabs = OpenTabs(panes)
  val terminals = TerminalRegistry(application, hostClient, scope, tabs)
  val fileHost = FileHost(hostClient, scope)
  val files = FileTabRegistry(fileHost, tabs)
  val gitHost = GitHost(hostClient, scope)
  val commits = CommitTabRegistry(gitHost, tabs, preferences.trustedRepositories, scope)
  val layout = WorkspaceLayout(this)

  /** What the chat screens keep of each chat, such as unsent drafts, held here so it survives rotation and folding. */
  val chatViews = ChatViews()

  init {
    holdWhileWorking()
    restartChatsOnceTermuxWorks()
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
   * A host that connects after a failure fixed in Termux (a setup step, or a helper that wasn't running) proves the
   * fix, so chats that failed on it start again instead of waiting on Retry.
   */
  private fun restartChatsOnceTermuxWorks() {
    scope.launch {
      var termuxBroken = false
      hostClient.lastFailure.collect { failure ->
        if (failure != null) {
          termuxBroken = termuxBroken || failure.fixedInTermux
          return@collect
        }
        if (!termuxBroken) return@collect
        termuxBroken = false
        for (process in registry.processes.value.values) {
          val state = process.state.value
          if (state is AgentProcessState.Failed && state.failure?.fixedInTermux == true) process.restart()
        }
      }
    }
  }

  /**
   * One [KeepAlive] acquisition while any chat holds its Pi (it works or waits on background tasks, with a short grace
   * after; see [AgentProcess.holdsPi]) or any terminal runs a command, so switching apps does not stop them. The
   * notification follows which of them keeps Pipkin awake, also while sign-in or a Pi install keeps it up.
   */
  private fun holdWhileWorking() {
    scope.launch {
      var hold: AutoCloseable? = null
      // Busy only changes the notification, which tells a working chat from one waiting on background tasks.
      combine(registry.busy, registry.holding, terminals.busy, ::Triple).collect { (_, chats, terminalsBusy) ->
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
