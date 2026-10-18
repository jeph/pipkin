package io.jeph.pipkin.ui.files

import androidx.annotation.DrawableRes
import io.jeph.pipkin.R

/**
 * File-type icons for the workspace file browser.
 *
 * The extension table is Paseo's `EXTENSION_TO_ICON` from
 * `packages/app/src/components/material-file-icons.ts`, and the drawables are its material-icon-theme
 * SVGs with the colour toning from `file-icon-svg.ts` baked in, as of Paseo commit b26b923
 * (https://github.com/getpaseo/paseo; Copyright (c) 2025-present Mohamed Boudra, Apache License 2.0, see
 * `assets/licenses/paseo.txt`; the icons are material-icon-theme's, MIT, see
 * `assets/licenses/material-icon-theme.txt`). They carry their own colours, so render them with
 * `Image(painterResource(...))` rather than a tinted `Icon`.
 */
private val extensionToIcon: Map<String, Int> = mapOf(
  "astro" to R.drawable.ic_file_astro,
  "bash" to R.drawable.ic_file_console,
  "c" to R.drawable.ic_file_c,
  "cfg" to R.drawable.ic_file_settings,
  "clj" to R.drawable.ic_file_clojure,
  "conf" to R.drawable.ic_file_settings,
  "cpp" to R.drawable.ic_file_cpp,
  "cs" to R.drawable.ic_file_csharp,
  "css" to R.drawable.ic_file_css,
  "dart" to R.drawable.ic_file_dart,
  "erl" to R.drawable.ic_file_erlang,
  "ex" to R.drawable.ic_file_elixir,
  "exs" to R.drawable.ic_file_elixir,
  "gif" to R.drawable.ic_file_image,
  "go" to R.drawable.ic_file_go,
  "gql" to R.drawable.ic_file_graphql,
  "gradle" to R.drawable.ic_file_gradle,
  "graphql" to R.drawable.ic_file_graphql,
  "groovy" to R.drawable.ic_file_groovy,
  "h" to R.drawable.ic_file_h,
  "hcl" to R.drawable.ic_file_hcl,
  "hpp" to R.drawable.ic_file_hpp,
  "hs" to R.drawable.ic_file_haskell,
  "html" to R.drawable.ic_file_html,
  "ico" to R.drawable.ic_file_image,
  "ini" to R.drawable.ic_file_settings,
  "java" to R.drawable.ic_file_java,
  "jpeg" to R.drawable.ic_file_image,
  "jpg" to R.drawable.ic_file_image,
  "js" to R.drawable.ic_file_javascript,
  "json" to R.drawable.ic_file_json,
  "jsx" to R.drawable.ic_file_react,
  "kt" to R.drawable.ic_file_kotlin,
  "less" to R.drawable.ic_file_less,
  "lock" to R.drawable.ic_file_lock,
  "lua" to R.drawable.ic_file_lua,
  "markdown" to R.drawable.ic_file_markdown,
  "md" to R.drawable.ic_file_markdown,
  "ml" to R.drawable.ic_file_ocaml,
  "nix" to R.drawable.ic_file_nix,
  "php" to R.drawable.ic_file_php,
  "png" to R.drawable.ic_file_image,
  "py" to R.drawable.ic_file_python,
  "r" to R.drawable.ic_file_r,
  "rb" to R.drawable.ic_file_ruby,
  "rs" to R.drawable.ic_file_rust,
  "scala" to R.drawable.ic_file_scala,
  "scss" to R.drawable.ic_file_sass,
  "sh" to R.drawable.ic_file_console,
  "sql" to R.drawable.ic_file_database,
  "svelte" to R.drawable.ic_file_svelte,
  "svg" to R.drawable.ic_file_svg,
  "swift" to R.drawable.ic_file_swift,
  "tf" to R.drawable.ic_file_terraform,
  "toml" to R.drawable.ic_file_toml,
  "ts" to R.drawable.ic_file_typescript,
  "tsx" to R.drawable.ic_file_react_ts,
  "txt" to R.drawable.ic_file_document,
  "vue" to R.drawable.ic_file_vue,
  "wasm" to R.drawable.ic_file_webassembly,
  "webp" to R.drawable.ic_file_image,
  "xml" to R.drawable.ic_file_xml,
  "yaml" to R.drawable.ic_file_yaml,
  "yml" to R.drawable.ic_file_yaml,
  "zig" to R.drawable.ic_file_zig,
)

/** Mirrors Paseo's `getRawFileIconSvg`: only the text after the last dot counts, case-insensitively. */
@DrawableRes
internal fun fileIcon(fileName: String): Int {
  val dot = fileName.lastIndexOf('.')
  if (dot == -1 || dot == fileName.length - 1) return R.drawable.ic_file_default
  return extensionToIcon[fileName.substring(dot + 1).lowercase()] ?: R.drawable.ic_file_default
}
