package io.jeph.pipkin.data

import androidx.annotation.StringRes
import io.jeph.pipkin.R

/**
 * The Pi extensions Pipkin bundles, which Settings can turn off. Each one's source is the APK asset [asset]
 * (`host/build.ts` bundles it), which the host's `ensure` installs under [id]. [usesRuntimePackages] marks one that
 * imports packages the bundled `pi/package.json` installs, which `ensure` installs inside that runtime so Node finds them.
 */
internal enum class BundledExtension(
  val id: String,
  @StringRes val title: Int,
  @StringRes val summary: Int,
  val asset: String,
  val usesRuntimePackages: Boolean = false,
) {
  Todo(
    "pipkin-todo",
    R.string.settings_extension_todo,
    R.string.settings_extension_todo_summary,
    asset = "pipkin-extensions/pipkin-todo.js",
  ),

  /** Runs shell commands past the agent's turn; it tells the app over Pipkin's channel while they run. */
  Background(
    "pipkin-background",
    R.string.settings_extension_background,
    R.string.settings_extension_background_summary,
    asset = "pipkin-extensions/pipkin-background.js",
  ),

  /** pi-web-access, vendored in `host/src/pi-extensions/pipkin-web-access`. */
  WebAccess(
    "pipkin-web-access",
    R.string.settings_extension_web_access,
    R.string.settings_extension_web_access_summary,
    asset = "pipkin-extensions/pipkin-web-access.js",
    usesRuntimePackages = true,
  ),
}
