package io.jeph.pipkin.workspace

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The open tabs, oldest first, and what each pane shows, kept so a restarted Pipkin shows them again. */
internal data class SavedLayout(val tabs: List<SavedTab>, val panes: Map<Pane, SavedPaneItem>)

/** An open tab of the workspace folder [cwd]. */
internal sealed interface SavedTab {
  val cwd: String

  /** A terminal, "Terminal [number]" until the user named it [name]; a restored one starts a new shell. */
  data class Terminal(override val cwd: String, val number: Int, val name: String?) : SavedTab

  data class File(override val cwd: String, val path: String) : SavedTab

  data class Commit(override val cwd: String, val sha: String, val subject: String, val authorTime: Long) : SavedTab
}

internal sealed interface SavedPaneItem {
  /** The tab at [index] in [SavedLayout.tabs]. */
  data class Tab(val index: Int) : SavedPaneItem

  /** A chat in [cwd], in the session file [sessionPath], or a new one before Pi named its session file. */
  data class Chat(val cwd: String, val sessionPath: String?) : SavedPaneItem
}

internal fun SavedLayout.toJson(): String = JSONObject()
  .put("tabs", JSONArray(tabs.map { it.toJson() }))
  .put("panes", JSONObject(panes.entries.associate { (pane, item) -> pane.name to item.toJson() }))
  .toString()

/** The layout [json] holds, or null when it isn't one, so a damaged one is dropped rather than half restored. */
internal fun savedLayoutOf(json: String): SavedLayout? = try {
  val root = JSONObject(json)
  val tabs = root.getJSONArray("tabs").let { array -> List(array.length()) { array.getJSONObject(it).toSavedTab() } }
  val panes = root.getJSONObject("panes")
  SavedLayout(
    tabs = tabs,
    panes = Pane.entries
      .mapNotNull { pane -> panes.optJSONObject(pane.name)?.toSavedPaneItem(tabs.size)?.let { pane to it } }
      .toMap(),
  )
} catch (_: JSONException) {
  null
}

private fun SavedTab.toJson(): JSONObject {
  val json = JSONObject().put("cwd", cwd)
  return when (this) {
    is SavedTab.Terminal -> json.put("kind", "terminal").put("number", number).put("name", name ?: JSONObject.NULL)

    is SavedTab.File -> json.put("kind", "file").put("path", path)

    is SavedTab.Commit ->
      json.put("kind", "commit").put("sha", sha).put("subject", subject).put("authorTime", authorTime)
  }
}

private fun JSONObject.toSavedTab(): SavedTab {
  val cwd = getString("cwd")
  return when (val kind = getString("kind")) {
    "terminal" -> SavedTab.Terminal(cwd, getInt("number"), optNullableString("name"))
    "file" -> SavedTab.File(cwd, getString("path"))
    "commit" -> SavedTab.Commit(cwd, getString("sha"), getString("subject"), getLong("authorTime"))
    else -> throw JSONException("Unknown tab kind $kind")
  }
}

private fun SavedPaneItem.toJson(): JSONObject = when (this) {
  is SavedPaneItem.Tab -> JSONObject().put("tab", index)
  is SavedPaneItem.Chat -> JSONObject().put("cwd", cwd).put("sessionPath", sessionPath ?: JSONObject.NULL)
}

private fun JSONObject.toSavedPaneItem(tabCount: Int): SavedPaneItem {
  if (!has("tab")) return SavedPaneItem.Chat(getString("cwd"), optNullableString("sessionPath"))
  val index = getInt("tab")
  if (index !in 0 until tabCount) throw JSONException("Tab $index of $tabCount")
  return SavedPaneItem.Tab(index)
}

private fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else getString(name)
