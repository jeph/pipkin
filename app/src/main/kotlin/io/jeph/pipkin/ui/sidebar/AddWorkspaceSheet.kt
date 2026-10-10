package io.jeph.pipkin.ui.sidebar

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.jeph.pipkin.ui.components.PipkinSheet

/**
 * Picks a folder to add as a sidebar workspace with [FolderBrowserPane], starting in Termux [home].
 * Folders [isWorkspace] already accepts can't be added again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddWorkspaceSheet(
  home: String,
  isWorkspace: (cwd: String) -> Boolean,
  onAdd: (cwd: String) -> Unit,
  onDismiss: () -> Unit,
) {
  // A steady share of the screen, so the sheet doesn't resize as folders with more or fewer entries open.
  PipkinSheet(onDismissRequest = onDismiss, heightFraction = SHEET_HEIGHT_FRACTION) {
    FolderBrowserPane(home = home, isWorkspace = isWorkspace, modifier = Modifier.weight(1f), onChoose = onAdd)
  }
}

private const val SHEET_HEIGHT_FRACTION = 0.8f
