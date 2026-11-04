package io.jeph.pipkin.data

import androidx.annotation.StringRes
import io.jeph.pipkin.R

/**
 * The Pi extensions Pipkin bundles. Each one's source is the APK asset [asset] (`host/build.ts` bundles it), which the
 * host's `ensure` installs under [id]. [usesRuntimePackages] marks one that imports packages the bundled
 * `pi/package.json` installs, which `ensure` installs inside that runtime so Node finds them. One with a [switch] is
 * listed in Settings, which can turn it off; one without is part of how Pipkin works, so every chat's Pi gets it.
 */
internal enum class BundledExtension(
  val id: String,
  val switch: Switch?,
  val asset: String,
  val usesRuntimePackages: Boolean = false,
) {
  Todo(
    "pipkin-todo",
    Switch(R.string.settings_extension_todo, R.string.settings_extension_todo_summary),
    asset = "pipkin-extensions/pipkin-todo.js",
  ),

  /** Runs shell commands past the agent's turn; it tells the app over Pipkin's channel while they run. */
  Background(
    "pipkin-background",
    Switch(R.string.settings_extension_background, R.string.settings_extension_background_summary),
    asset = "pipkin-extensions/pipkin-background.js",
  ),

  /** pi-web-access, vendored in `host/src/pi-extensions/pipkin-web-access`. */
  WebAccess(
    "pipkin-web-access",
    Switch(R.string.settings_extension_web_access, R.string.settings_extension_web_access_summary),
    asset = "pipkin-extensions/pipkin-web-access.js",
    usesRuntimePackages = true,
  ),

  /** Hands the agent's tasks to subagents in the same Pi; it tells the app about their runs over Pipkin's channel. */
  Subagents(
    "pipkin-subagents",
    Switch(R.string.settings_extension_subagents, R.string.settings_extension_subagents_summary),
    asset = "pipkin-extensions/pipkin-subagents.js",
  ),

  /**
   * Runs Pi's file and shell tools on an SSH host. The host's `rpc` loads it only in a remote workspace, where the
   * chat can't work without it, so there's no switch.
   */
  Remote(
    "pipkin-remote",
    switch = null,
    asset = "pipkin-extensions/pipkin-remote.js",
  ),
  ;

  /** How Settings lists an extension the user can turn off. */
  class Switch(@StringRes val title: Int, @StringRes val summary: Int)
}
