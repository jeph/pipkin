package io.jeph.pipkin.data

import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saves the chat picker's model and thinking level as Pi's defaults in `settings.json`, which new
 * chats start with. Saves run one at a time in pick order, so the last pick is the one kept.
 */
internal class ModelDefaults(private val hostClient: HostClient) {
  private val lock = Mutex()

  /** Returns the user-facing failure, or null once saved. */
  suspend fun saveModel(provider: String, modelId: String, fallbackError: String): String? =
    save(fallbackError) { hostClient.setDefaultModel(provider, modelId) }

  /** Returns the user-facing failure, or null once saved. */
  suspend fun saveThinkingLevel(level: String, fallbackError: String): String? =
    save(fallbackError) { hostClient.setDefaultThinkingLevel(level) }

  private suspend fun save(fallbackError: String, block: suspend () -> Unit): String? = lock.withLock {
    try {
      block()
      null
    } catch (rejected: HostRejected) {
      rejected.error
    } catch (failure: IOException) {
      failure.message?.takeIf(String::isNotBlank) ?: fallbackError
    }
  }
}
