package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.CustomProvider
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.host.ProviderConfig
import java.io.IOException

/** The endpoint details a connection check covers; changing any of them makes the check stale. */
internal data class Connection(val baseUrl: String, val apiKey: String, val api: String)

/** Result of listing the endpoint's models with the key, if any, which proves it is reachable and accepts the key. */
internal sealed interface ConnectionCheck {
  data object Idle : ConnectionCheck

  data class Checking(val connection: Connection) : ConnectionCheck

  data class Ok(val connection: Connection, val models: List<ModelConfig>) : ConnectionCheck

  /**
   * [code] is the host's cause (`unauthorized`, `not_found`, `network`, …), or null when the check didn't reach the
   * host; [message] is what the host or the server said.
   */
  data class Failed(val connection: Connection, val code: String?, val message: String) : ConnectionCheck
}

/**
 * Draft of an OpenAI-compatible provider, shared by the provider, add-models and model pages until
 * it is saved. When [editing], the id is fixed. Host errors land in [idError] or [formError].
 */
@Stable
internal class ProviderForm(val editing: Boolean, initialId: String) : ModelDraft {
  var id by mutableStateOf(initialId)
  var baseUrl by mutableStateOf("")
  var apiKey by mutableStateOf("")
  override var api by mutableStateOf(ProviderConfig.API_COMPLETIONS)
  private val models = mutableStateListOf<ModelForm>()
  var saving by mutableStateOf(false)
    private set
  var idError by mutableStateOf<String?>(null)
    private set
  var formError by mutableStateOf<String?>(null)
    private set
  var prefilled by mutableStateOf(false)
    private set
  private var lastCheck by mutableStateOf<ConnectionCheck>(ConnectionCheck.Idle)

  /**
   * The latest failed check, kept through edits and the next check until one succeeds, so the error stays in place
   * while the user fixes what it describes instead of disappearing and coming back.
   */
  var lastFailure by mutableStateOf<ConnectionCheck.Failed?>(null)
    private set
  private var baseline = snapshot()

  private val connection: Connection get() = Connection(baseUrl.trim(), apiKey.trim(), api)

  /** The last check, or [ConnectionCheck.Idle] once the URL, key or API type changed after it. */
  val check: ConnectionCheck
    get() = when (val last = lastCheck) {
      ConnectionCheck.Idle -> last
      is ConnectionCheck.Checking -> last.takeIf { it.connection == connection }
      is ConnectionCheck.Ok -> last.takeIf { it.connection == connection }
      is ConnectionCheck.Failed -> last.takeIf { it.connection == connection }
    } ?: ConnectionCheck.Idle

  val connected: Boolean get() = check is ConnectionCheck.Ok

  /** A check ran, but the URL, key or API type changed since. */
  val checkOutdated: Boolean get() = lastCheck != ConnectionCheck.Idle && check == ConnectionCheck.Idle

  /** What the endpoint listed in the current check. */
  val fetched: List<ModelConfig> get() = (check as? ConnectionCheck.Ok)?.models.orEmpty()

  val idValid: Boolean get() = ID_PATTERN.matches(id)
  val hasBaseUrl: Boolean get() = baseUrl.isNotBlank()
  val canCheck: Boolean get() = hasBaseUrl && check !is ConnectionCheck.Checking
  val canSave: Boolean
    get() = idValid && hasBaseUrl && models.isNotEmpty() && models.all { it.valid } && !saving
  val dirty: Boolean get() = snapshot() != baseline

  /** Fills the draft from the stored provider once; later status updates do not overwrite edits. */
  fun prefill(provider: CustomProvider) {
    if (prefilled) return
    prefilled = true
    baseUrl = provider.baseUrl
    apiKey = provider.apiKey
    api = provider.api.takeIf { it == ProviderConfig.API_RESPONSES } ?: ProviderConfig.API_COMPLETIONS
    models.clear()
    models.addAll(provider.models.map(::ModelForm))
    baseline = snapshot()
  }

  suspend fun checkConnection(accounts: AccountRepository, fallbackError: String) {
    val target = connection
    val checking = ConnectionCheck.Checking(target)
    lastCheck = checking
    var result: ConnectionCheck = ConnectionCheck.Idle
    try {
      result = ConnectionCheck.Ok(target, accounts.providerModels(target.baseUrl, target.apiKey, target.api))
    } catch (rejected: HostRejected) {
      result = ConnectionCheck.Failed(target, rejected.code, rejected.error)
    } catch (failure: IOException) {
      result = ConnectionCheck.Failed(target, null, failure.message ?: fallbackError)
    } finally {
      // Cancelled with its screen, the check goes back to idle rather than staying "checking";
      // a check superseded by a newer one leaves that one's state alone.
      if (lastCheck === checking) {
        lastCheck = result
        when (result) {
          is ConnectionCheck.Ok -> lastFailure = null
          is ConnectionCheck.Failed -> lastFailure = result
          else -> Unit
        }
      }
    }
  }

  override val canAddModels: Boolean get() = connected
  override val available: List<ModelConfig> get() = fetched
  override val added: List<ModelForm> get() = models
  override val custom = CustomModelInput()

  override fun isAdded(modelId: String): Boolean = model(modelId) != null

  override fun model(modelId: String): ModelForm? = models.firstOrNull { it.id == modelId }

  override fun toggle(config: ModelConfig) {
    if (isAdded(config.id)) remove(config.id) else models.add(ModelForm(config))
  }

  override fun remove(modelId: String) {
    models.removeAll { it.id == modelId }
  }

  override suspend fun addCustom(lookup: suspend (String) -> ModelConfig, fallbackError: String) {
    custom.add(lookup, fallbackError, exists = ::isAdded) { models.add(ModelForm(it)) }
  }

  /** True once `models.json` holds the provider. */
  suspend fun save(accounts: AccountRepository, fallbackError: String): Boolean {
    val configs = models.mapNotNull { it.toConfig() }
    if (configs.size != models.size) return false
    return submit(fallbackError) {
      accounts.saveProvider(ProviderConfig(id, baseUrl.trim(), api, apiKey.trim(), configs))
      baseline = snapshot()
    }
  }

  /** True once the provider is gone from `models.json`. */
  suspend fun removeProvider(accounts: AccountRepository, fallbackError: String): Boolean =
    submit(fallbackError) { accounts.removeProvider(id) }

  private fun snapshot(): List<Any?> = listOf(id, baseUrl, apiKey, api, models.map { it.snapshot() })

  private suspend fun submit(fallbackError: String, change: suspend () -> Unit): Boolean {
    saving = true
    idError = null
    formError = null
    try {
      change()
      return true
    } catch (rejected: HostRejected) {
      if (rejected.code in ID_CODES) idError = rejected.error else formError = rejected.error
    } catch (failure: IOException) {
      formError = failure.message ?: fallbackError
    } finally {
      saving = false
    }
    return false
  }

  private companion object {
    /** Same rule as the host's `provider_save`. */
    val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    val ID_CODES = setOf("bad_id", "builtin_id")
  }
}
