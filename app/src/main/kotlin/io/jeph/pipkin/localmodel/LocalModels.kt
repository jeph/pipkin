package io.jeph.pipkin.localmodel

import android.content.Context
import android.util.Log
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.data.Preferences
import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.host.TermuxBuild
import io.jeph.pipkin.localmodel.catalog.ModelStore
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * On-device models: the downloaded files ([store]), the process serving one of them to Pi ([runtime]), and Pi's
 * `pipkin-local` provider, kept listing exactly the downloaded models while the feature is on.
 */
internal class LocalModels(
  context: Context,
  private val scope: CoroutineScope,
  keepAlive: KeepAlive,
  private val accounts: AccountRepository,
  private val preferences: Preferences,
) {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The key Pi's provider presents; any app can reach loopback, so the endpoint answers only this. */
  private val apiKey: String = prefs.getString(KEY_API_KEY, null) ?: randomKey().also {
    prefs.edit().putString(KEY_API_KEY, it).apply()
  }

  val store = ModelStore(context, scope, preferences, keepAlive)
  val runtime =
    LocalModelRuntime(context, scope, keepAlive, store.installed, preferences) { apiKey }

  init {
    scope.launch { preferences.localModelsEnabled.collect(runtime::serve) }
    syncProvider()
  }

  /**
   * Writes the provider on every start and whenever what it should list changes, since a backup restore, removing the
   * provider or reinstalling Pi can change models.json behind Pipkin's back. Retries while the Termux host can't be
   * reached or refuses, as it does while Pi is being installed. An empty list is only written once a provider was, so
   * a feature that was never on doesn't call the host.
   */
  private fun syncProvider() {
    scope.launch {
      combine(preferences.localModelsEnabled, store.installed) { on, installed ->
        if (on) installed.map(::modelConfig) else emptyList()
      }
        .distinctUntilChanged()
        .collectLatest { models ->
          if (models.isEmpty() && !prefs.getBoolean(KEY_WRITTEN, false)) return@collectLatest
          while (true) {
            try {
              accounts.syncLocalModels("http://127.0.0.1:${TermuxBuild.LOCAL_MODEL_PORT}/v1", apiKey, models)
              prefs.edit().putBoolean(KEY_WRITTEN, models.isNotEmpty()).apply()
              return@collectLatest
            } catch (failure: HostFailure) {
              Log.w(TAG, "Couldn't reach the host to update Pi's local models; retrying", failure)
            } catch (failure: HostRejected) {
              Log.w(TAG, "The host refused to update Pi's local models (${failure.code}); retrying", failure)
            }
            delay(RETRY_MILLIS)
          }
        }
    }
  }

  private fun modelConfig(installed: InstalledModel): ModelConfig = ModelConfig(
    id = installed.model.id,
    name = installed.model.name,
    reasoning = installed.model.reasoning,
    contextWindow = installed.contextSize.toLong(),
    maxTokens = (installed.contextSize / REPLY_SHARE).toLong(),
  )

  private companion object {
    const val TAG = "PipkinLocalModels"
    const val PREFS = "local-models"
    const val KEY_API_KEY = "api-key"

    /** Whether Pi's config lists local models, so an empty list must be written to remove them. */
    const val KEY_WRITTEN = "provider-written"
    const val RETRY_MILLIS = 30_000L
    const val KEY_BYTES = 24

    /**
     * A reply may take at most an eighth of the context: 2,048 tokens of 16,384, or 4,096 of 32,768. Pi's system prompt
     * and tools take about 7,000 tokens, and the host keeps the reply and pi-ai's 4,096-token margin free on top of the
     * conversation, so a larger share would leave too little of a 16,384-token context for the conversation itself.
     */
    const val REPLY_SHARE = 8

    fun randomKey(): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes))
  }
}
