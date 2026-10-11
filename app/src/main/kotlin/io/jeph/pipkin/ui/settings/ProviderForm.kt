package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.CustomProvider
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ProviderConfig
import java.io.IOException

internal data class ModelChoice(val id: String, val checked: Boolean)

/**
 * Editable state of the provider editor. When [editing], the id is fixed and the stored key is
 * filled in. Host errors land in [idError], [fetchError] or [formError].
 */
@Stable
internal class ProviderForm(val editing: Boolean, initialId: String) {
  var id by mutableStateOf(initialId)
  var baseUrl by mutableStateOf("")
  var apiKey by mutableStateOf("")
  var api by mutableStateOf(ProviderConfig.API_COMPLETIONS)
  var images by mutableStateOf(false)
  val models = mutableStateListOf<ModelChoice>()
  var manualModel by mutableStateOf("")
  var fetching by mutableStateOf(false)
    private set
  var saving by mutableStateOf(false)
    private set
  var idError by mutableStateOf<String?>(null)
    private set
  var fetchError by mutableStateOf<String?>(null)
    private set
  var formError by mutableStateOf<String?>(null)
    private set
  private var prefilled = false

  val idValid: Boolean get() = ID_PATTERN.matches(id)
  val canFetch: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && !fetching
  val canSave: Boolean
    get() = idValid && baseUrl.isNotBlank() && apiKey.isNotBlank() && models.any { it.checked } && !saving

  /** Fills the form from the stored provider once; later status updates do not overwrite edits. */
  fun prefill(provider: CustomProvider) {
    if (prefilled) return
    prefilled = true
    baseUrl = provider.baseUrl
    apiKey = provider.apiKey
    api = provider.api.takeIf { it == ProviderConfig.API_RESPONSES } ?: ProviderConfig.API_COMPLETIONS
    images = provider.images
    models.clear()
    models.addAll(provider.models.map { ModelChoice(it, checked = true) })
  }

  fun setChecked(index: Int, checked: Boolean) {
    models[index] = models[index].copy(checked = checked)
  }

  fun addManualModel() {
    val model = manualModel.trim()
    if (model.isEmpty()) return
    val index = models.indexOfFirst { it.id == model }
    if (index >= 0) setChecked(index, true) else models.add(ModelChoice(model, checked = true))
    manualModel = ""
  }

  suspend fun fetchModels(accounts: AccountRepository, fallbackError: String) {
    fetching = true
    fetchError = null
    try {
      addFetched(accounts.providerModels(baseUrl.trim(), apiKey.trim()))
    } catch (rejected: HostRejected) {
      fetchError = rejected.error
    } catch (failure: IOException) {
      fetchError = failure.message ?: fallbackError
    } finally {
      fetching = false
    }
  }

  /** True once `models.json` holds the provider. */
  suspend fun save(accounts: AccountRepository, fallbackError: String): Boolean = submit(fallbackError) {
    accounts.saveProvider(
      ProviderConfig(
        id = id,
        baseUrl = baseUrl.trim(),
        api = api,
        apiKey = apiKey.trim(),
        images = images,
        models = models.filter { it.checked }.map { it.id },
      ),
    )
  }

  /** True once the provider is gone from `models.json`. */
  suspend fun remove(accounts: AccountRepository, fallbackError: String): Boolean =
    submit(fallbackError) { accounts.removeProvider(id) }

  /** The first fetch checks everything; later fetches only add new ids unchecked so earlier choices stand. */
  private fun addFetched(ids: List<String>) {
    val checkNew = models.isEmpty()
    val known = models.mapTo(HashSet()) { it.id }
    ids.filterNot(known::contains).forEach { models.add(ModelChoice(it, checked = checkNew)) }
  }

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
