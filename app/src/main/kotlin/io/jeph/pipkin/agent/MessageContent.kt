package io.jeph.pipkin.agent

import org.json.JSONArray

/** Text blocks of a Pi message's content array, joined by newlines; image blocks are skipped. */
internal fun JSONArray?.text(): String {
  if (this == null) return ""
  return (0 until length())
    .mapNotNull { optJSONObject(it)?.takeIf { block -> block.optString("type") == "text" }?.optString("text") }
    .joinToString("\n")
}

/** Image blocks of a Pi message's content array, in order. */
internal fun JSONArray?.images(): List<ImagePart> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { index ->
    optJSONObject(index)?.takeIf { it.optString("type") == "image" }?.let {
      ImagePart(it.optString("data"), it.optString("mimeType"))
    }
  }
}
