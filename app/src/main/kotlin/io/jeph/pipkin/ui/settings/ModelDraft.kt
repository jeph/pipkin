package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

  /** The models on the provider page, in order. */
  val added: List<ModelForm>

  val custom: CustomModelInput

  fun isAdded(modelId: String): Boolean

  /** Adds the model, or takes it off the provider page when it is already there. */
  fun toggle(config: ModelConfig)

  fun model(modelId: String): ModelForm?

  /** Takes the model off the provider page. */
  fun remove(modelId: String)

  /** Adds [custom]'s id, looked up in Pi's catalogue through [lookup]. */
  suspend fun addCustom(lookup: suspend (String) -> ModelConfig, fallbackError: String)
}

/** The add-models page's "custom model" field. */
@Stable
internal class CustomModelInput {
  var text by mutableStateOf("")
  var adding by mutableStateOf(false)
    private set
  var error by mutableStateOf<String?>(null)
    private set

  /** Looks the typed id up and hands it to [onAdded]; an id [exists] already only clears the field. */
  suspend fun add(
    lookup: suspend (String) -> ModelConfig,
    fallbackError: String,
    exists: (String) -> Boolean,
    onAdded: (ModelConfig) -> Unit,
  ) {
    val modelId = text.trim()
    if (modelId.isEmpty() || adding) return
    if (exists(modelId)) {
      text = ""
      return
    }
    adding = true
    error = null
    try {
      onAdded(lookup(modelId))
      text = ""
    } catch (rejected: HostRejected) {
      error = rejected.error
    } catch (failure: IOException) {
      error = failure.message ?: fallbackError
    } finally {
      adding = false
    }
  }
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
