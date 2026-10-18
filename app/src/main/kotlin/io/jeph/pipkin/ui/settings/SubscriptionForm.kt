package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.BuiltinProvider
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ModelConfig
import java.io.IOException

/**
 * Draft of the models of a provider Pi ships: every model Pi offers for it, which of them show (Pi's
 * `enabledModels`), and their settings (`modelOverrides`, or extra models Pi doesn't know).
 */
@Stable
internal class SubscriptionForm(val providerId: String) : ModelDraft {
  private val forms = mutableStateListOf<ModelForm>()
  private val enabled = mutableStateListOf<String>()
  private val extraIds = mutableStateListOf<String>()
  var prefilled by mutableStateOf(false)
    private set
  var saving by mutableStateOf(false)
    private set
  var formError by mutableStateOf<String?>(null)
    private set
  private var baseline = snapshot()
  override val api: String = subscriptions.firstOrNull { it.id == providerId }?.api.orEmpty()

  override val canAddModels: Boolean get() = prefilled
  override val available: List<ModelConfig> get() = forms.map(ModelForm::preview)
  override val added: List<ModelForm> get() = forms.filter { it.id in enabled }
  override val custom = CustomModelInput()

  val canSave: Boolean get() = prefilled && forms.all { it.valid } && !saving
  val dirty: Boolean get() = snapshot() != baseline

  /** Fills the draft from Pi's state; once the user has edited it, later status updates are ignored. */
  fun prefill(provider: BuiltinProvider) {
    if (prefilled && dirty) return
    prefilled = true
    forms.clear()
    forms.addAll(provider.models.map(::ModelForm))
    extraIds.clear()
    extraIds.addAll(provider.extraIds)
    enabled.clear()
    enabled.addAll(provider.enabled ?: provider.models.map { it.id })
    baseline = snapshot()
  }

  override fun isAdded(modelId: String): Boolean = modelId in enabled

  override fun toggle(config: ModelConfig) {
    if (!enabled.remove(config.id)) enabled.add(config.id)
  }

  override fun model(modelId: String): ModelForm? = forms.firstOrNull { it.id == modelId }

  /** Hides a built-in model; an extra model is deleted with its settings. */
  override fun remove(modelId: String) {
    enabled.remove(modelId)
    if (extraIds.remove(modelId)) forms.removeAll { it.id == modelId }
  }

  override suspend fun addCustom(lookup: suspend (String) -> ModelConfig, fallbackError: String) {
    custom.add(lookup, fallbackError, exists = { model(it) != null }) {
      forms.add(ModelForm(it))
      extraIds.add(it.id)
      enabled.add(it.id)
    }
  }

  /** True once Pi's files hold the draft. */
  suspend fun save(accounts: AccountRepository, fallbackError: String): Boolean {
    val configs = forms.mapNotNull { it.toConfig() }
    if (configs.size != forms.size) return false
    saving = true
    formError = null
    try {
      accounts.saveSubscription(providerId, forms.map { it.id }.filter { it in enabled }, configs)
      baseline = snapshot()
      return true
    } catch (rejected: HostRejected) {
      formError = rejected.error
    } catch (failure: IOException) {
      formError = failure.message ?: fallbackError
    } finally {
      saving = false
    }
    return false
  }

  private fun snapshot(): List<Any?> = listOf(enabled.toSet(), extraIds.toList(), forms.map { it.snapshot() })
}
