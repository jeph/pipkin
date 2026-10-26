package io.jeph.pipkin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.jeph.pipkin.ui.components.SnackbarStackState
import kotlinx.coroutines.CoroutineScope

/**
 * What the chats a pane shows share, outliving each of them: the snackbars, so a message about a chat that left (delete
 * failed) still shows over the next, and the scope chat commands run in, so one that switches chats still finishes.
 */
@Stable
internal class PaneChats(val snackbar: SnackbarStackState, val scope: CoroutineScope)

@Composable
internal fun rememberPaneChats(): PaneChats {
  val scope = rememberCoroutineScope()
  return remember(scope) { PaneChats(SnackbarStackState(), scope) }
}
