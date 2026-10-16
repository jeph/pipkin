package io.jeph.pipkin.agent

import org.json.JSONObject

/**
 * A widget an extension set with `ctx.ui.setWidget` (Pi `docs/rpc-extension-ui.md`): its lines as Pi sent them, with
 * Pi's ANSI styling left in, and whether Pi's terminal UI draws it below the editor rather than above it.
 */
internal data class ExtensionWidget(val lines: List<String>, val belowEditor: Boolean)

/**
 * Applies one `setWidget` request to [widgets]. Pi sends no `widgetLines` to remove a widget, and Pi's terminal UI
 * draws nothing for an empty list, so that removes it too. A widget keeps its place among the others when its lines
 * change, so tabs don't jump while an extension updates one, but moves to the end of the other edge when its
 * placement changes, the way Pi's terminal UI adds it there. Null when [event] has no `widgetKey`.
 */
internal fun withWidget(widgets: Map<String, ExtensionWidget>, event: JSONObject): Map<String, ExtensionWidget>? {
  val key = event.opt("widgetKey") as? String ?: return null
  val array = event.optJSONArray("widgetLines")
  val lines = List(array?.length() ?: 0) { (array?.opt(it) as? String).orEmpty() }
  if (lines.isEmpty()) return widgets - key
  val widget = ExtensionWidget(lines, belowEditor = event.optString("widgetPlacement") == "belowEditor")
  val moved = widgets[key]?.let { it.belowEditor != widget.belowEditor } == true
  return if (moved) widgets - key + (key to widget) else widgets + (key to widget)
}
