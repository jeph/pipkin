package io.jeph.pipkin.data

import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.LoginUi
import io.jeph.pipkin.host.ProviderConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Sign-in state and custom providers. Every successful change re-reads [status] and reloads idle
 * agents so they pick up the new credentials. Changes throw [HostFailure][io.jeph.pipkin.host.HostFailure]
 * or [HostRejected].
 */
internal class AccountRepository(
  private val hostClient: HostClient,
  private val registry: AgentRegistry<*>,
  private val keepAlive: KeepAlive,
) {
  private val statusState = MutableStateFlow<AuthStatus?>(null)
  private val refreshErrorState = MutableStateFlow<String?>(null)
  private val refreshLock = Mutex()

  /** Null until the first successful refresh. */
  val status: StateFlow<AuthStatus?> = statusState.asStateFlow()

  /** Why the last refresh failed; null after a successful one. */
  val refreshError: StateFlow<String?> = refreshErrorState.asStateFlow()

  /** Re-reads `auth_status`. Never throws; a failure lands in [refreshError]. */
  suspend fun refresh() {
    refreshLock.withLock {
      refreshErrorState.value = try {
        statusState.value = hostClient.authStatus()
        null
      } catch (failure: IOException) {
        failure.message ?: REFRESH_FAILED
      } catch (rejected: HostRejected) {
        rejected.error
      } catch (error: JSONException) {
        error.message ?: REFRESH_FAILED
      }
    }
  }

  /** OAuth sign-in for `anthropic` or `openai-codex`; cancelling the caller aborts it. */
  suspend fun login(providerId: String, ui: LoginUi) {
    keepAlive.acquire("sign-in").use { hostClient.login(providerId, ui) }
    changed()
  }

  suspend fun logout(providerId: String) {
    hostClient.logout(providerId)
    changed()
  }

  /** Sorted model ids the endpoint lists; nothing is saved. */
  suspend fun providerModels(baseUrl: String, apiKey: String): List<String> = hostClient.providerModels(baseUrl, apiKey)

  suspend fun saveProvider(provider: ProviderConfig) {
    hostClient.providerSave(provider)
    changed()
  }

  suspend fun removeProvider(id: String) {
    hostClient.providerRemove(id)
    changed()
  }

  private suspend fun changed() {
    refresh()
    // The registry is main-thread confined.
    withContext(Dispatchers.Main.immediate) { registry.reloadIdle() }
  }

  private companion object {
    const val REFRESH_FAILED = "Could not read sign-in status."
  }
}
