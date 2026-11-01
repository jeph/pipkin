package io.jeph.pipkin.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.PillTone
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Restore and Delete for the selection; Restore counts only chats whose folder still exists. */
@Composable
internal fun BulkActions(
  restoreCount: Int,
  deleteCount: Int,
  enabled: Boolean,
  onRestore: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = pipkinColors
  Column(modifier = Modifier.fillMaxWidth().background(colors.surface0).navigationBarsPadding()) {
    HorizontalDivider(thickness = 1.dp, color = colors.border)
    Row(
      modifier = Modifier.fillMaxWidth().padding(PipkinTokens.Spacing.s12),
      horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
    ) {
      PillButton(
        label = stringResource(R.string.archive_restore_count, restoreCount),
        onClick = onRestore,
        modifier = Modifier.weight(1f),
        tone = PillTone.Tonal,
        enabled = enabled && restoreCount > 0,
      )
      PillButton(
        label = stringResource(R.string.archive_delete_count, deleteCount),
        onClick = onDelete,
        modifier = Modifier.weight(1f),
        tone = PillTone.Destructive,
        enabled = enabled,
      )
    }
  }
}
