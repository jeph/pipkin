package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.data.AccountRepository
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.ui.Destination
import java.io.IOException

/** The models of a provider draft, as the add-models and model pages edit them. */
internal interface ModelDraft {
  /** The Pi API the models use, which picks between catalogue entries sharing an id. */
  val api: String

  /** False until the draft can offer models, for example before the connection is checked. */
  val canAddModels: Boolean

  /** What the add-models page lists. */
  val available: List<ModelConfig>

  /** True while [loadAvailable] asks the provider for its models. */
  val loadingAvailable: Boolean get() = false

  /** Why the provider's own list of models couldn't be read; [available] then falls back to Pi's catalogue. */
  val availableError: String? get() = null

  /** The models on the provider page, in order. */
  val added: List<ModelForm>

  val customLookup: CustomModelLookup

  fun isAdded(modelId: String): Boolean

  /** Adds the model, or takes it off the provider page when it is already there. */
  fun toggle(config: ModelConfig)

  fun model(modelId: String): ModelForm?

  /** Takes the model off the provider page. */
  fun remove(modelId: String)

  /** Adds [modelId] as a custom model, looked up in Pi's catalogue through [lookup]; [customLookup] tracks it. */
  suspend fun addCustom(modelId: String, lookup: suspend (String) -> ModelConfig, fallbackError: String)

  /** Fills [available] for the add-models page, for example from the provider's own list of models. */
  suspend fun loadAvailable(accounts: AccountRepository, fallbackError: String) = Unit
}

/**
 * Adding a custom model typed into the add-models page's search: which id is being looked up, and why the last one
 * failed. Both belong to an id, so a search changed while a lookup runs never shows another id's result.
 */
@Stable
internal class CustomModelLookup {
  /** The id being looked up; while it runs, no other is. */
  var addingId by mutableStateOf<String?>(null)
    private set
  private var failure by mutableStateOf<Failure?>(null)

  /** Why adding [modelId] failed the last time, or null. */
  fun errorFor(modelId: String): String? = failure?.takeIf { it.modelId == modelId }?.message

  /** Looks [modelId] up and hands it to [onAdded], unless [addKnown] adds it from what the draft already has. */
  suspend fun add(
    modelId: String,
    lookup: suspend (String) -> ModelConfig,
    fallbackError: String,
    addKnown: (String) -> Boolean,
    onAdded: (ModelConfig) -> Unit,
  ) {
    if (modelId.isEmpty() || addingId != null || addKnown(modelId)) return
    addingId = modelId
    failure = null
    try {
      onAdded(lookup(modelId))
    } catch (rejected: HostRejected) {
      failure = Failure(modelId, rejected.error)
    } catch (unreachable: IOException) {
      failure = Failure(modelId, unreachable.message ?: fallbackError)
    } finally {
      addingId = null
    }
  }

  private class Failure(val modelId: String, val message: String)
}

/** The provider or subscription draft while its page is on the screen stack, so its sub-pages share it. */
internal class ProviderDrafts {
  private var key: String? = null
  private var draft: ModelDraft? = null

  /** The draft for custom provider [id] (null for a new one), reused while it stays open. */
  fun provider(id: String?): ProviderForm = reuse("provider:${id.orEmpty()}") {
    ProviderForm(editing = id != null, initialId = id.orEmpty())
  }

  fun subscription(id: String): SubscriptionForm = reuse("subscription:$id") { SubscriptionForm(id) }

  /** The draft of the nearest subscription or provider page in [stack]. */
  fun owner(stack: List<Destination>): ModelDraft = when (val page = stack.last(::ownsDraft)) {
    is Destination.Subscription -> subscription(page.id)
    is Destination.ProviderEditor -> provider(page.id)
    else -> error("$page owns no draft")
  }

  fun release() {
    key = null
    draft = null
  }

  private inline fun <reified T : ModelDraft> reuse(draftKey: String, create: () -> T): T {
    if (key == draftKey) (draft as? T)?.let { return it }
    return create().also {
      key = draftKey
      draft = it
    }
  }
}

/** True for the pages whose model sub-pages edit their draft. */
internal fun ownsDraft(d: Destination): Boolean = d is Destination.Subscription || d is Destination.ProviderEditor
