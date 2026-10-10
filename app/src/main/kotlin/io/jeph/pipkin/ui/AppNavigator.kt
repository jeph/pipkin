package io.jeph.pipkin.ui

import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A full screen of the app; [AppNavigator] stacks them. */
sealed interface Destination {
  data object Main : Destination

  data object Settings : Destination

  /** [id] null adds a provider; otherwise edits the custom provider with that id. */
  data class ProviderEditor(val id: String?) : Destination

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
      Destination.Onboarding -> ONBOARDING
      is Destination.ProviderEditor -> PROVIDER + d.id.orEmpty()
    }

    private fun destination(key: String): Destination? = when {
      key == MAIN -> Destination.Main
      key == SETTINGS -> Destination.Settings
      key == ONBOARDING -> Destination.Onboarding
      key.startsWith(PROVIDER) -> Destination.ProviderEditor(key.removePrefix(PROVIDER).ifEmpty { null })
      else -> null
    }

    private const val MAIN = "main"
    private const val SETTINGS = "settings"
    private const val ONBOARDING = "onboarding"
    private const val PROVIDER = "provider:"
  }
}
