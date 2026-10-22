package io.jeph.pipkin.ui.files

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.ui.components.PipkinMarkdown
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * Paseo's markdown preview: the file rendered in a reading frame, under a table of its front matter. Only web links
 * open, as in Paseo; a link to another file of the workspace does nothing.
 */
@Composable
internal fun MarkdownPreview(source: String, modifier: Modifier) {
  val document = remember(source) { parseMarkdownDocument(source) }
  val navigationBar = WindowInsets.navigationBars.asPaddingValues()
  val platform = LocalUriHandler.current
  val webOnly = remember(platform) { WebOnlyUriHandler(platform) }
  CompositionLocalProvider(LocalUriHandler provides webOnly) {
    Box(
      modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(
          start = READING_INSET,
          end = READING_INSET,
          top = PipkinTokens.Spacing.s16,
          bottom = PipkinTokens.Spacing.s16 + navigationBar.calculateBottomPadding(),
        ),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(modifier = Modifier.widthIn(max = PipkinTokens.contentMaxWidth).fillMaxWidth()) {
        if (document.frontMatter.isNotEmpty()) FrontMatterTable(document.frontMatter)
        PipkinMarkdown(document.body, Modifier.fillMaxWidth())
      }
    }
  }
}

/** Paseo's front matter table: muted monospace keys on surface2 beside their values, in a bordered card. */
@Composable
private fun FrontMatterTable(rows: List<Pair<String, String>>) {
  val colors = pipkinColors
  val shape = RoundedCornerShape(PipkinTokens.Radius.lg)
  SelectionContainer {
    BoxWithConstraints(
      modifier = Modifier
        .padding(bottom = PipkinTokens.Spacing.s24)
        .fillMaxWidth()
        .clip(shape)
        .border(1.dp, colors.border, shape),
    ) {
      // The key column's background is drawn behind each row, so it spans the row whichever cell is taller.
      val keyWidth = min(maxWidth * KEY_SHARE, KEY_MAX_WIDTH)
      Column {
        rows.forEachIndexed { index, (key, value) ->
          if (index > 0) HorizontalDivider(thickness = 1.dp, color = colors.border)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .drawBehind { drawRect(colors.surface2, size = Size(keyWidth.toPx(), size.height)) },
          ) {
            Text(
              text = key,
              modifier = Modifier.width(keyWidth).padding(CELL_PADDING_HORIZONTAL, CELL_PADDING_VERTICAL),
              color = colors.foregroundMuted,
              fontFamily = PipkinFonts.mono,
              fontSize = PipkinTokens.FontSize.base,
              lineHeight = TABLE_LINE_HEIGHT,
            )
            Text(
              text = value,
              modifier = Modifier.weight(1f).padding(CELL_PADDING_HORIZONTAL, CELL_PADDING_VERTICAL),
              color = colors.foreground,
              fontSize = PipkinTokens.FontSize.base,
              lineHeight = TABLE_LINE_HEIGHT,
            )
          }
        }
      }
    }
  }
}

/** Opens http and https links with [platform] and ignores the rest, which Android has no app to open. */
private class WebOnlyUriHandler(private val platform: UriHandler) : UriHandler {
  override fun openUri(uri: String) {
    if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
      platform.openUri(uri)
    }
  }
}

/** Paseo's 12dp gutter plus the reading frame's 8dp. */
private val READING_INSET = 20.dp

private const val KEY_SHARE = 0.3f

private val KEY_MAX_WIDTH = 180.dp

private val TABLE_LINE_HEIGHT = 20.sp

private val CELL_PADDING_HORIZONTAL = PipkinTokens.Spacing.s12

private val CELL_PADDING_VERTICAL = PipkinTokens.Spacing.s8
