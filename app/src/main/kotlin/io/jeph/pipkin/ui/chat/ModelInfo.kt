package io.jeph.pipkin.ui.chat

import org.json.JSONObject

/** Pi Model JSON's display name, falling back to its id; null without a model. */
internal fun modelLabel(model: JSONObject?): String? =
  model?.let { (it.opt("name") as? String)?.takeIf(String::isNotBlank) ?: it.optString("id") }

/** True when the model's `input` includes `image`. */
internal fun acceptsImages(model: JSONObject?): Boolean {
  val input = model?.optJSONArray("input") ?: return false
  return (0 until input.length()).any { input.optString(it) == "image" }
}

/** Pi marks models that can think with `reasoning: true`; the others only offer "off". */
internal fun supportsThinking(model: JSONObject?): Boolean = model?.optBoolean("reasoning") == true

internal fun levelLabel(level: String): String = level.replaceFirstChar { it.uppercaseChar() }

internal fun isSameModel(model: JSONObject, current: JSONObject?): Boolean = current != null &&
  model.optString("provider") == current.optString("provider") &&
  model.optString("id") == current.optString("id")
