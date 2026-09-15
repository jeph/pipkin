package io.jeph.pipkin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R

@Composable
internal fun SettingsScreen() {
  Column(
    modifier = Modifier.fillMaxSize().safeDrawingPadding()
      .verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Text(
      text = stringResource(R.string.settings_title),
      style = MaterialTheme.typography.headlineLarge,
      modifier = Modifier.semantics { heading() },
    )
    TermuxSettings()
  }
}
