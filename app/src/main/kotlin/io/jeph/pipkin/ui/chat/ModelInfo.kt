package io.jeph.pipkin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
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

/** The name of Pi's thinking [level] in the app's language; a level Pipkin doesn't know shows capitalized as Pi names it. */
@Composable
internal fun thinkingLevelLabel(level: String): String {
  val label = when (level) {
    "off" -> R.string.model_level_off
    "minimal" -> R.string.model_level_minimal
    "low" -> R.string.model_level_low
    "medium" -> R.string.model_level_medium
    "high" -> R.string.model_level_high
    "xhigh" -> R.string.model_level_xhigh
    "max" -> R.string.model_level_max
    else -> return level.replaceFirstChar { it.uppercaseChar() }
  }
  return stringResource(label)
}

internal fun isSameModel(model: JSONObject, current: JSONObject?): Boolean = current != null &&
  model.optString("provider") == current.optString("provider") &&
  model.optString("id") == current.optString("id")
