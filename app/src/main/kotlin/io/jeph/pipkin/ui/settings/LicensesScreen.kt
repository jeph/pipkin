package io.jeph.pipkin.ui.settings

import android.content.res.AssetManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The licence files of the code, fonts and icons the app ships: `src/main/assets/licenses` and the host build's. */
private const val COMPONENT_LICENSES = "licenses"

/** tree-sitter's and each grammar's licence, which the `syntaxSources` build step copies out of their sources. */
private const val SYNTAX_LICENSES = "syntax/licenses"

/** The third-party licences the app ships, one row each, reached from Settings › About. */
@Composable
internal fun LicensesScreen(onBack: () -> Unit, onOpenLicense: (path: String) -> Unit) {
  val assets = LocalContext.current.assets
  val components = remember(assets) { licensePaths(assets, COMPONENT_LICENSES) }
  val grammars = remember(assets) { licensePaths(assets, SYNTAX_LICENSES) }
  SettingsPage(title = stringResource(R.string.settings_licenses), onBack = onBack) {
    SettingsCaption(text = stringResource(R.string.settings_licenses_caption))
    LicenseSection(stringResource(R.string.settings_licenses_components), components, onOpenLicense)
    LicenseSection(stringResource(R.string.settings_licenses_syntax), grammars, onOpenLicense)
  }
}

/** The text of the licence asset at [path], selectable for copying. */
@Composable
internal fun LicenseScreen(path: String, onBack: () -> Unit) {
  val assets = LocalContext.current.assets
  val text by produceState<String?>(null, path) {
    value = withContext(Dispatchers.IO) { assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
  }
  SettingsPage(title = licenseName(path), onBack = onBack) {
    text?.let {
      SelectionContainer {
        Text(
          text = it,
          color = pipkinColors.foreground,
          fontFamily = PipkinFonts.mono,
          fontSize = PipkinTokens.FontSize.code,
          lineHeight = PipkinTokens.LineHeight.code,
        )
      }
    }
  }
}

@Composable
private fun LicenseSection(title: String, paths: List<String>, onOpenLicense: (path: String) -> Unit) {
  SettingsSection(title = title) {
    paths.forEachIndexed { index, path ->
      if (index > 0) SettingsDivider()
      SettingsRow(title = licenseName(path), onClick = { onOpenLicense(path) }) {
        LucideIcon(
          icon = R.drawable.ic_lucide_chevron_right,
          contentDescription = null,
          tint = pipkinColors.foregroundMuted,
        )
      }
    }
  }
}

/** The asset paths of the licence files in [dir], by name, so `perl` comes before `perl-bsearch`. */
private fun licensePaths(assets: AssetManager, dir: String): List<String> =
  assets.list(dir).orEmpty().filter { it.endsWith(LICENSE_SUFFIX) }.map { "$dir/$it" }.sortedBy(::licenseName)

/** A licence file's name, which names the component it covers. */
private fun licenseName(path: String): String = path.substringAfterLast('/').removeSuffix(LICENSE_SUFFIX)

private const val LICENSE_SUFFIX = ".txt"
