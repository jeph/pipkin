package io.jeph.pipkin.ui.chat

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/** Returns a function that puts plain text on the clipboard; Android shows its own copied confirmation. */
@Composable
internal fun rememberCopyText(): (String) -> Unit {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  return remember(clipboard, scope) {
    { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Pipkin", text))) } }
  }
}
