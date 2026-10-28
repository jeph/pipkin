package io.jeph.pipkin.localmodel

import android.content.Context
import android.util.Log
import io.jeph.pipkin.agent.KeepAlive
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.host.TermuxBuild
import io.jeph.pipkin.localmodel.catalog.ModelStore
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * On-device models: the downloaded files ([store]), the process serving one of them to Pi ([runtime]), and Pi's
 * `pipkin-local` provider, kept listing exactly the downloaded models while [enabled].
 */
internal class LocalModels(
  context: Context,
  private val scope: CoroutineScope,
  keepAlive: KeepAlive,
  private val accounts: AccountRepository,
  private val enabled: StateFlow<Boolean>,
  idleUnloadMinutes: StateFlow<Int>,
) {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The key Pi's provider presents; any app can reach loopback, so the endpoint answers only this. */
  private val apiKey: String = prefs.getString(KEY_API_KEY, null) ?: randomKey().also {
    prefs.edit().putString(KEY_API_KEY, it).apply()
  }

  val store = ModelStore()
  val runtime = LocalModelRuntime(context, scope, keepAlive, store.installed, idleUnloadMinutes) { apiKey }

  init {
    scope.launch { enabled.collect(runtime::serve) }
    syncProvider()
  }

  /**
   * Writes the provider whenever what it should list changes, retrying while the Termux host can't be reached or
   * refuses, as it does while Pi is being installed. Nothing was ever written on a fresh install, which is the same as
   * an empty list, so a disabled feature never calls the host.
   */
  private fun syncProvider() {
    scope.launch {
      combine(enabled, store.installed) { on, installed -> if (on) installed.map(::modelConfig) else emptyList() }
        .distinctUntilChanged()
        .collectLatest { models ->
          val signature = models.joinToString("\n") { it.toJson().toString() }
          if (signature == prefs.getString(KEY_SYNCED, "")) return@collectLatest
          while (true) {
            try {
              accounts.syncLocalModels("http://127.0.0.1:${TermuxBuild.LOCAL_MODEL_PORT}/v1", apiKey, models)
              prefs.edit().putString(KEY_SYNCED, signature).apply()
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

  private fun modelConfig(installed: InstalledModel): ModelConfig {
    val context = installed.artifact.contextSize
    return ModelConfig(
      id = installed.model.id,
      name = installed.model.name,
      reasoning = installed.model.reasoning,
      contextWindow = context.toLong(),
      maxTokens = minOf(installed.model.maxTokens, context / MAX_TOKENS_SHARE).toLong(),
    )
  }

  private companion object {
    const val TAG = "PipkinLocalModels"
    const val PREFS = "local-models"
    const val KEY_API_KEY = "api-key"

    /** The provider list last written to Pi, so starting the app doesn't rewrite an unchanged one. */
    const val KEY_SYNCED = "synced-provider"
    const val RETRY_MILLIS = 30_000L
    const val KEY_BYTES = 24

    /** A reply may take at most a quarter of the context, as Pi's compaction reserves for it. */
    const val MAX_TOKENS_SHARE = 4

    fun randomKey(): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes))
  }
}
