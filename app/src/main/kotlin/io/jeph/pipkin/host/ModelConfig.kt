package io.jeph.pipkin.host

import org.json.JSONArray
import org.json.JSONObject

/**
 * The `models.json` model fields Pipkin edits; null means the field is absent and Pi uses its
 * default. The host keeps every other field an entry has.
 */
internal data class ModelConfig(
  val id: String,
  val name: String? = null,
  val reasoning: Boolean? = null,
  /** Pi thinking level to the value sent to the provider; a null value marks the level unsupported. */
  val thinkingLevelMap: Map<String, String?>? = null,
  val images: Boolean? = null,
  val contextWindow: Long? = null,
  val maxTokens: Long? = null,
  val cost: ModelCost? = null,
) {
  fun toJson(): JSONObject {
    val json = JSONObject().put("id", id)
    name?.let { json.put("name", it) }
    reasoning?.let { json.put("reasoning", it) }
    thinkingLevelMap?.let { map ->
      json.put(
        "thinkingLevelMap",
        JSONObject().also { obj ->
          map.forEach { (level, value) ->
            obj.put(
              level,
              value ?: JSONObject.NULL,
            )
          }
        },
      )
    }
    images?.let { json.put("input", JSONArray(if (it) listOf(TEXT, IMAGE) else listOf(TEXT))) }
    contextWindow?.let { json.put("contextWindow", it) }
    maxTokens?.let { json.put("maxTokens", it) }
    cost?.let { json.put("cost", it.toJson()) }
    return json
  }

  companion object {
    /** Pi's thinking levels, lowest first. */
    val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    private const val TEXT = "text"
    private const val IMAGE = "image"

    fun from(json: JSONObject) = ModelConfig(
      id = json.getString("id"),
      name = json.opt("name") as? String,
      reasoning = json.opt("reasoning") as? Boolean,
      thinkingLevelMap = json.optJSONObject("thinkingLevelMap")?.let { map ->
        THINKING_LEVELS.filter(map::has).associateWith { level ->
          if (map.isNull(level)) null else map.getString(level)
        }
      },
      images = json.optJSONArray("input")?.let { input ->
        List(input.length()) { input.optString(it) }.contains(IMAGE)
      },
      contextWindow = (json.opt("contextWindow") as? Number)?.toLong(),
      maxTokens = (json.opt("maxTokens") as? Number)?.toLong(),
      cost = json.optJSONObject("cost")?.let(ModelCost::from),
    )
  }
}

/** Prices in US dollars per million tokens; Pi needs all four together. */
internal data class ModelCost(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double) {
  fun toJson(): JSONObject = JSONObject()
    .put("input", input)
    .put("output", output)
    .put("cacheRead", cacheRead)
    .put("cacheWrite", cacheWrite)

  companion object {
    fun from(json: JSONObject): ModelCost? {
      val rates = RATE_KEYS.mapNotNull { (json.opt(it) as? Number)?.toDouble() }
      return if (rates.size == RATE_KEYS.size) ModelCost(rates[0], rates[1], rates[2], rates[3]) else null
    }

    private val RATE_KEYS = listOf("input", "output", "cacheRead", "cacheWrite")
  }
}
