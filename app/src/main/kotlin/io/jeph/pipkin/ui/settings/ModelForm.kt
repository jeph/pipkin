package io.jeph.pipkin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.host.ModelConfig
import io.jeph.pipkin.host.ModelCost
import java.math.BigDecimal

/** Editable settings of one model in the provider draft; numbers stay text until [toConfig]. */
@Stable
internal class ModelForm(config: ModelConfig) {
  val id: String = config.id
  var name by mutableStateOf(config.name.orEmpty())
  var images by mutableStateOf(config.images == true)
  var reasoning by mutableStateOf(config.reasoning == true)

  /** Pi's `thinkingLevelMap`; null leaves every level at Pi's default. */
  var levelMap by mutableStateOf(config.thinkingLevelMap)
    private set
  var contextWindow by mutableStateOf(config.contextWindow?.toString().orEmpty())
  var maxTokens by mutableStateOf(config.maxTokens?.toString().orEmpty())
  var costInput by mutableStateOf(config.cost?.input.price())
  var costOutput by mutableStateOf(config.cost?.output.price())
  var costCacheRead by mutableStateOf(config.cost?.cacheRead.price())
  var costCacheWrite by mutableStateOf(config.cost?.cacheWrite.price())

  /** The provider value each level had, so turning one back on restores it (Off is often `"none"`). */
  private val levelValues: Map<String, String> =
    ModelConfig.THINKING_LEVELS.associateWith { if (it == OFF) OFF_VALUE else it } +
      config.thinkingLevelMap.orEmpty().mapNotNull { (level, value) -> value?.let { level to it } }

  val contextWindowInvalid: Boolean get() = !isCount(contextWindow)
  val maxTokensInvalid: Boolean get() = !isCount(maxTokens)

  /** Prices are all optional, but input and output come together and every price is a number of 0 or more. */
  val costInvalid: Boolean
    get() {
      val prices = listOf(costInput, costOutput, costCacheRead, costCacheWrite)
      if (prices.all(String::isBlank)) return false
      return costInput.isBlank() || costOutput.isBlank() || prices.any { it.isNotBlank() && price(it) == null }
    }

  val valid: Boolean get() = !contextWindowInvalid && !maxTokensInvalid && !costInvalid

  /** Pi offers a level unless the map marks it null; `xhigh` and `max` need an explicit value. */
  fun levelEnabled(level: String): Boolean {
    val map = levelMap
    if (map == null || !map.containsKey(level)) return level !in EXPLICIT_LEVELS
    return map[level] != null
  }

  fun setLevel(level: String, enabled: Boolean) {
    levelMap = levelMap.orEmpty() + (level to if (enabled) levelValues.getValue(level) else null)
  }

  /** The model as saved, or null while a field is invalid. */
  fun toConfig(): ModelConfig? {
    if (!valid) return null
    val prices = listOf(costInput, costOutput, costCacheRead, costCacheWrite).map { price(it) ?: 0.0 }
    return ModelConfig(
      id = id,
      name = name.trim().ifEmpty { null },
      reasoning = reasoning,
      thinkingLevelMap = levelMap.takeIf { reasoning },
      images = images,
      contextWindow = contextWindow.trim().toLongOrNull(),
      maxTokens = maxTokens.trim().toLongOrNull(),
      cost = if (costInput.isBlank()) null else ModelCost(prices[0], prices[1], prices[2], prices[3]),
    )
  }

  /** The current settings for display, with unparsable numbers left out. */
  fun preview(): ModelConfig = ModelConfig(
    id = id,
    name = name.trim().ifEmpty { null },
    reasoning = reasoning,
    images = images,
    contextWindow = contextWindow.trim().toLongOrNull(),
  )

  /** Everything the user can change, for detecting unsaved edits. */
  fun snapshot(): List<Any?> = listOf(
    id, name, images, reasoning, levelMap, contextWindow, maxTokens,
    costInput, costOutput, costCacheRead, costCacheWrite,
  )

  private companion object {
    const val OFF = "off"
    const val OFF_VALUE = "none"
    val EXPLICIT_LEVELS = setOf("xhigh", "max")

    fun isCount(text: String): Boolean = text.isBlank() || (text.trim().toLongOrNull() ?: 0) > 0

    fun price(text: String): Double? = text.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.toDouble()

    fun Double?.price(): String = this?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()
  }
}
