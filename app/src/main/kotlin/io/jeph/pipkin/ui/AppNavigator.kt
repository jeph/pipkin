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
      Destination.Onboarding -> ONBOARDING
      Destination.Providers -> PROVIDERS
      Destination.AddProvider -> ADD_PROVIDER
      is Destination.Subscription -> SUBSCRIPTION + d.id
      is Destination.ProviderEditor -> PROVIDER + d.id.orEmpty()
      Destination.AddModels -> ADD_MODELS
      is Destination.ModelEditor -> MODEL + d.modelId
    }

    private fun destination(key: String): Destination? = when {
      key == MAIN -> Destination.Main
      key == SETTINGS -> Destination.Settings
      key == PERMISSIONS -> Destination.Permissions
      key == ONBOARDING -> Destination.Onboarding
      key == PROVIDERS -> Destination.Providers
      key == ADD_PROVIDER -> Destination.AddProvider
      key == ADD_MODELS -> Destination.AddModels
      key.startsWith(MODEL) -> Destination.ModelEditor(key.removePrefix(MODEL))
      key.startsWith(SUBSCRIPTION) -> Destination.Subscription(key.removePrefix(SUBSCRIPTION))
      key.startsWith(PROVIDER) -> Destination.ProviderEditor(key.removePrefix(PROVIDER).ifEmpty { null })
      else -> null
    }

    private const val MAIN = "main"
    private const val SETTINGS = "settings"
    private const val PERMISSIONS = "permissions"
    private const val ONBOARDING = "onboarding"
    private const val PROVIDER = "provider:"
    private const val PROVIDERS = "providers"
    private const val ADD_PROVIDER = "add-provider"
    private const val SUBSCRIPTION = "subscription:"
    private const val ADD_MODELS = "add-models"
    private const val MODEL = "model:"
  }
}
