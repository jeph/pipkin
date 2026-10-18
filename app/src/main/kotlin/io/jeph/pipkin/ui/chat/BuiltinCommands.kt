package io.jeph.pipkin.ui.chat

import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcessState
import io.jeph.pipkin.agent.RpcError
import io.jeph.pipkin.agent.cloneSession
import io.jeph.pipkin.agent.compact
import io.jeph.pipkin.agent.exportHtml
import io.jeph.pipkin.agent.fork
import io.jeph.pipkin.agent.forkMessages
import io.jeph.pipkin.agent.lastAssistantText
import io.jeph.pipkin.agent.sessionStats
import io.jeph.pipkin.ui.sidebar.tildePath
import kotlinx.coroutines.launch

/** What built-in commands open beyond the chat's own controller. */
internal interface ChatNavigator {
  fun openSheet(sheet: ChatSheet)

  /** A new chat in this chat's project. */
  fun newChat()

  /** The tab menu of the chats in this chat's workspace. */
  fun openTabs()

  fun openSettings()
}

/** Runs Pi's interactive commands with Pipkin's own screens and Pi's RPC equivalents. */
internal class BuiltinCommands(private val controller: ChatController, private val navigator: ChatNavigator) {
  private val process get() = controller.process

  fun run(invocation: BuiltinInvocation) {
    val argument = invocation.argument
    when (invocation.command) {
      BuiltinCommand.Settings, BuiltinCommand.Login, BuiltinCommand.Logout -> navigator.openSettings()

      BuiltinCommand.Model -> model(argument)

      BuiltinCommand.Thinking -> thinking(argument)

      BuiltinCommand.Export -> controller.launchRpc {
        controller.show(R.string.chat_exported, displayPath(process.exportHtml(argument)))
      }

      BuiltinCommand.Copy -> controller.launchRpc {
        val text = process.lastAssistantText()
        if (text.isNullOrBlank()) controller.show(R.string.chat_copy_nothing) else controller.copy(text)
      }

      BuiltinCommand.Name -> if (argument.isEmpty()) {
        navigator.openSheet(
          ChatSheet.Rename,
        )
      } else {
        controller.rename(argument)
      }

      BuiltinCommand.Session -> controller.launchRpc {
        navigator.openSheet(ChatSheet.SessionInfo(process.sessionStats()))
      }

      BuiltinCommand.Fork -> controller.launchRpc {
        val messages = process.forkMessages()
        if (messages.isEmpty()) {
          controller.show(
            R.string.chat_fork_nothing,
          )
        } else {
          navigator.openSheet(ChatSheet.Fork(messages))
        }
      }

      BuiltinCommand.Clone -> controller.launchRpc {
        controller.show(if (process.cloneSession()) R.string.chat_cloned else R.string.chat_cancelled_by_extension)
      }

      BuiltinCommand.New -> navigator.newChat()

      BuiltinCommand.Compact -> compact(argument)

      BuiltinCommand.Resume -> navigator.openTabs()

      BuiltinCommand.Reload ->
        if (process.holdsPi.value) navigator.openSheet(ChatSheet.ConfirmReload) else controller.reload()
    }
  }

  /**
   * Once Pi starts compacting it reports a failure in the stream as well as in its answer, so only a
   * Pi that never got the command (not running) gets a snackbar.
   */
  private fun compact(instructions: String) {
    controller.scope.launch {
      try {
        process.compact(instructions)
      } catch (error: RpcError) {
        if (process.state.value != AgentProcessState.Ready) controller.show(error.message)
      }
    }
  }

  /** Forks from the user message [entryId] and puts its text in the composer to edit and resend. */
  fun fork(entryId: String) = controller.launchRpc {
    val text = process.fork(entryId)
    if (text == null) {
      controller.show(R.string.chat_cancelled_by_extension)
    } else {
      controller.setDraftText(text)
      controller.show(R.string.chat_forked)
    }
  }

  /** `/model provider/id` (or a bare id) switches when exactly one model matches; otherwise the picker opens on it. */
  private fun model(query: String) {
    if (query.isEmpty()) return navigator.openSheet(ChatSheet.Model())
    controller.launchRpc {
      val match = process.availableModels().singleOrNull { model ->
        val id = model.optString("id")
        id.equals(query, ignoreCase = true) || "${model.optString("provider")}/$id".equals(query, ignoreCase = true)
      }
      if (match == null) {
        navigator.openSheet(ChatSheet.Model(query))
      } else {
        controller.setModel(match.optString("provider"), match.optString("id"))
      }
    }
  }

  /** `/thinking level` sets a level the model offers; `/thinking` alone opens the picker. */
  private fun thinking(level: String) {
    if (level.isEmpty()) return navigator.openSheet(ChatSheet.Thinking)
    controller.launchRpc {
      val levels = process.availableThinkingLevels()
      val match = levels.firstOrNull { it.equals(level, ignoreCase = true) }
      if (match == null) {
        controller.show(R.string.chat_thinking_levels, levels.joinToString())
      } else {
        controller.setThinkingLevel(match)
      }
    }
  }

  /** Pi resolves a relative export path against the chat's cwd; shown `~`-relative like other paths. */
  private fun displayPath(path: String): String {
    val absolute = if (path.startsWith("/")) path else "${process.cwd.trimEnd('/')}/$path"
    val home = controller.home ?: return absolute
    return tildePath(absolute, home)
  }
}
