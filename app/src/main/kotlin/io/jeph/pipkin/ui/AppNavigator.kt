package io.jeph.pipkin.ui

import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A full screen of the app; [AppNavigator] stacks them. */
sealed interface Destination {
  data object Main : Destination

  data object Settings : Destination

  /** Termux setup and Pipkin's Android permissions, reached from Settings. */
  data object Permissions : Destination

  /** How many chats keep their agent running and when idle ones stop, reached from Settings › Agent. */
  data object RunningAgents : Destination

  /** Backups of Pi's configuration in Termux, reached from Settings. */
  data object Backups : Destination

  /** The third-party licences the app ships, reached from Settings › About. */
  data object Licenses : Destination

  /** The licence asset at [path], one of [Licenses]. */
  data class License(val path: String) : Destination

  /** The list of added providers. */
  data object Providers : Destination

  /** Picks which kind of provider to add. */
  data object AddProvider : Destination

  /** A subscription Pipkin signs in to (`anthropic` or `openai-codex`). */
  data class Subscription(val id: String) : Destination

  /** [id] null adds a provider; otherwise edits the custom provider with that id. */
  data class ProviderEditor(val id: String?) : Destination

  /** Adds models to the draft of the nearest subscription or provider page below it. */
  data object AddModels : Destination

  /** One model of the draft of the nearest subscription or provider page below it. */
  data class ModelEditor(val modelId: String) : Destination

  data object Onboarding : Destination

  /** Archived chats, with search, restore and delete. */
  data object Archive : Destination

  /** The read-only transcript of the archived chat in session file [path]. */
  data class ArchivedChat(val path: String) : Destination

  /**
   * The read-only transcript of the subagent the archived chat in session file [path] started with its `subagent`
   * tool call [toolCallId].
   */
  data class ArchivedSubagent(val path: String, val toolCallId: String) : Destination
}

/** The screen stack; never empty. Main thread only. */
class AppNavigator(initial: List<Destination>) {
  private val stackState = MutableStateFlow(initial.ifEmpty { listOf(Destination.Main) })

  val stack: StateFlow<List<Destination>> = stackState.asStateFlow()

  fun push(d: Destination) {
    stackState.value += d
  }

  /** Removes the top screen; false when only one is left, so the caller lets the system handle back. */
  fun pop(): Boolean {
    val current = stackState.value
    if (current.size <= 1) return false
    stackState.value = current.dropLast(1)
    return true
  }

  /** Pops back to the topmost [d]; pops one screen when [d] is not on the stack. */
  fun popTo(d: Destination) {
    val current = stackState.value
    val index = current.lastIndexOf(d)
    if (index < 0) pop() else stackState.value = current.take(index + 1)
  }

  fun replaceAll(d: Destination) {
    stackState.value = listOf(d)
  }

  companion object {
    /** Keeps the stack across activity recreation as [key] strings. */
    val Saver: Saver<AppNavigator, List<String>> = Saver(
      save = { navigator -> navigator.stack.value.map(::key) },
      restore = { keys -> AppNavigator(keys.mapNotNull(::destination)) },
    )

    /** A stable string per destination, also used as its saveable-state key. */
    fun key(d: Destination): String = when (d) {
      Destination.Main -> MAIN

      Destination.Settings -> SETTINGS

      Destination.Permissions -> PERMISSIONS

      Destination.RunningAgents -> RUNNING_AGENTS

      Destination.Backups -> BACKUPS

      Destination.Licenses -> LICENSES

      is Destination.License -> LICENSE + d.path

      Destination.Onboarding -> ONBOARDING

      Destination.Providers -> PROVIDERS

      Destination.AddProvider -> ADD_PROVIDER

      is Destination.Subscription -> SUBSCRIPTION + d.id

      is Destination.ProviderEditor -> PROVIDER + d.id.orEmpty()

      Destination.AddModels -> ADD_MODELS

      is Destination.ModelEditor -> MODEL + d.modelId

      Destination.Archive -> ARCHIVE

      is Destination.ArchivedChat -> ARCHIVED_CHAT + d.path

      // A tool call id has no line break; a path comes last, since it may hold any other character.
      is Destination.ArchivedSubagent -> ARCHIVED_SUBAGENT + d.toolCallId + "\n" + d.path
    }

    private fun destination(key: String): Destination? = when {
      key == MAIN -> Destination.Main

      key == SETTINGS -> Destination.Settings

      key == PERMISSIONS -> Destination.Permissions

      key == RUNNING_AGENTS -> Destination.RunningAgents

      key == BACKUPS -> Destination.Backups

      key == LICENSES -> Destination.Licenses

      key.startsWith(LICENSE) -> Destination.License(key.removePrefix(LICENSE))

      key == ONBOARDING -> Destination.Onboarding

      key == PROVIDERS -> Destination.Providers

      key == ADD_PROVIDER -> Destination.AddProvider

      key == ADD_MODELS -> Destination.AddModels

      key == ARCHIVE -> Destination.Archive

      key.startsWith(ARCHIVED_CHAT) -> Destination.ArchivedChat(key.removePrefix(ARCHIVED_CHAT))

      key.startsWith(ARCHIVED_SUBAGENT) -> key.removePrefix(ARCHIVED_SUBAGENT).let { rest ->
        Destination.ArchivedSubagent(path = rest.substringAfter('\n'), toolCallId = rest.substringBefore('\n'))
      }

      key.startsWith(MODEL) -> Destination.ModelEditor(key.removePrefix(MODEL))

      key.startsWith(SUBSCRIPTION) -> Destination.Subscription(key.removePrefix(SUBSCRIPTION))

      key.startsWith(PROVIDER) -> Destination.ProviderEditor(key.removePrefix(PROVIDER).ifEmpty { null })

      else -> null
    }

    private const val MAIN = "main"
    private const val SETTINGS = "settings"
    private const val PERMISSIONS = "permissions"
    private const val RUNNING_AGENTS = "running-agents"
    private const val BACKUPS = "backups"
    private const val LICENSES = "licenses"
    private const val LICENSE = "license:"
    private const val ONBOARDING = "onboarding"
    private const val PROVIDER = "provider:"
    private const val PROVIDERS = "providers"
    private const val ADD_PROVIDER = "add-provider"
    private const val SUBSCRIPTION = "subscription:"
    private const val ADD_MODELS = "add-models"
    private const val MODEL = "model:"
    private const val ARCHIVE = "archive"
    private const val ARCHIVED_CHAT = "archived-chat:"
    private const val ARCHIVED_SUBAGENT = "archived-subagent:"
  }
}
