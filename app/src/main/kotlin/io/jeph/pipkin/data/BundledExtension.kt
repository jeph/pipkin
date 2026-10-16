package io.jeph.pipkin.data

import androidx.annotation.StringRes
import io.jeph.pipkin.R

/**
 * The Pi extensions Pipkin bundles, which Settings can turn off. Each is either a package in the bundled
 * `pi/package.json`, named [id], or one Pipkin makes itself, whose source is the APK asset [asset]
 * (`host/build.ts` bundles it) and which the host saves under [id].
 */
internal enum class BundledExtension(
  val id: String,
  @StringRes val title: Int,
  @StringRes val summary: Int,
  val asset: String? = null,
) {
  Todo(
    "pipkin-todo",
    R.string.settings_extension_todo,
    R.string.settings_extension_todo_summary,
    asset = "pipkin-extensions/pipkin-todo.js",
  ),
  WebAccess("pi-web-access", R.string.settings_extension_web_access, R.string.settings_extension_web_access_summary),
}
