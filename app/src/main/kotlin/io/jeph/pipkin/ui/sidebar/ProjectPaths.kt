package io.jeph.pipkin.ui.sidebar

/** [cwd] with the Termux [home] prefix shown as `~`. */
internal fun tildePath(cwd: String, home: String): String = when {
  cwd == home -> "~"
  cwd.startsWith("$home/") -> "~" + cwd.removePrefix(home)
  else -> cwd
}

/** A project's display name: its folder's name, or `~` for home. */
internal fun projectName(cwd: String, home: String): String =
  if (cwd == home) "~" else cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }

/**
 * The index of the place [path] lies in, or -1 when it is in none (for example `/`). The longest
 * match wins, so a place inside another claims its own folders.
 */
internal fun placeIndexOf(placePaths: List<String>, path: String): Int = placePaths.indices
  .filter { path == placePaths[it] || path.startsWith(placePaths[it].trimEnd('/') + "/") }
  .maxByOrNull { placePaths[it].length } ?: -1
