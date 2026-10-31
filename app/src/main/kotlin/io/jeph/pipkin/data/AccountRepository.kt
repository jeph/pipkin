package io.jeph.pipkin.data

import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.host.AuthStatus
import io.jeph.pipkin.host.HostClient
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.LoginUi
import io.jeph.pipkin.host.ModelConfig
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
        failure.message ?: refreshFailed()
      } catch (rejected: HostRejected) {
        rejected.error
      } catch (error: JSONException) {
        error.message ?: refreshFailed()
      }
    }
  }

  /** Sign-in to a provider Pi ships, by OAuth or API key; cancelling the caller aborts it. */
  suspend fun login(providerId: String, ui: LoginUi) {
    keepAlive.acquire("sign-in").use { hostClient.login(providerId, ui) }
    changed()
  }

  suspend fun logout(providerId: String) {
    hostClient.logout(providerId)
    changed()
  }

  /** The endpoint's models with detected settings; nothing is saved. Succeeding also proves the key works. */
  suspend fun providerModels(baseUrl: String, apiKey: String, api: String): List<ModelConfig> =
    hostClient.providerModels(baseUrl, apiKey, api)

  suspend fun modelDefaults(ids: List<String>, api: String): List<ModelConfig> = hostClient.modelDefaults(ids, api)

  /** What the provider says its stored key may use, or null when it has no such list; nothing is saved. */
  suspend fun subscriptionModels(providerId: String): List<ModelConfig>? = hostClient.subscriptionModels(providerId)

  suspend fun saveProvider(provider: ProviderConfig) {
    hostClient.providerSave(provider)
    changed()
  }

  suspend fun saveSubscription(providerId: String, enabled: List<String>, models: List<ModelConfig>) {
    hostClient.subscriptionSave(providerId, enabled, models)
    changed()
  }

  suspend fun removeProvider(id: String) {
    hostClient.providerRemove(id)
    changed()
  }

  /**
   * Makes Pi's `pipkin-local` provider list exactly [models], served at [baseUrl]; idle chats restart to see them only
   * when that changed Pi's configuration, since this runs on every start, while the chat shown first is starting.
   */
  suspend fun syncLocalModels(baseUrl: String, apiKey: String, models: List<ModelConfig>) {
    if (hostClient.localModelsSync(baseUrl, apiKey, models)) changed()
  }

  /** Installs a Pi package with `pi install`; idle chats restart to load it. Cancelling the caller aborts it. */
  suspend fun installPackage(source: String, onProgress: (String) -> Unit) {
    keepAlive.acquire("Pi install").use { hostClient.installPackage(source, onProgress) }
    changed()
  }

  private suspend fun changed() {
    refresh()
    // The registry is main-thread confined.
    withContext(Dispatchers.Main.immediate) { registry.reloadIdle() }
  }

  private fun refreshFailed() = AppStrings.get(R.string.settings_auth_refresh_failed)
}
