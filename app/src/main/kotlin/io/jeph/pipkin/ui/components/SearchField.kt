package io.jeph.pipkin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** A single-line search field: a search icon and [placeholder] in a surface2 pill. */
@Composable
fun SearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
) {
  val colors = pipkinColors
  BasicTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = modifier.fillMaxWidth().padding(
      horizontal = PipkinTokens.Spacing.s16,
      vertical = PipkinTokens.Spacing.s4,
    ),
    textStyle = LocalTextStyle.current.merge(
      TextStyle(color = colors.foreground, fontSize = PipkinTokens.FontSize.base),
    ),
    cursorBrush = SolidColor(colors.accent),
    keyboardOptions = keyboardOptions,
    singleLine = true,
    decorationBox = { field ->
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(colors.surface2, CircleShape)
            .padding(horizontal = PipkinTokens.Spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        LucideIcon(icon = R.drawable.ic_lucide_search, contentDescription = null, tint = colors.foregroundMuted)
        Box(modifier = Modifier.weight(1f)) {
          if (query.isEmpty()) {
            Text(text = placeholder, color = colors.foregroundMuted, fontSize = PipkinTokens.FontSize.base)
          }
          field()
        }
      }
    },
  )
}
