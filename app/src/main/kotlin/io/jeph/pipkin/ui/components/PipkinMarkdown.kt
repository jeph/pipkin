package io.jeph.pipkin.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.resolveImageAlt
import io.jeph.pipkin.ui.theme.PipkinFonts
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.flow.conflate
import org.intellij.markdown.MarkdownElementTypes

/**
 * Assistant markdown: 15/22sp body, and code on raised cards that scroll sideways, headed by their language. With a
 * [cache], markdown it has parsed before shows from the first frame, and each parse of it is kept there.
 */
@Composable
fun PipkinMarkdown(markdown: String, modifier: Modifier = Modifier, cache: MarkdownCache? = null) {
  val colors = pipkinColors
  val body = TextStyle(
    color = colors.foreground,
    fontFamily = PipkinFonts.ui,
    fontSize = PipkinTokens.FontSize.content,
    lineHeight = PipkinTokens.LineHeight.markdown,
    fontFeatureSettings = PipkinFonts.FEATURES,
  )
  // CodeText is Pipkin's one code style, so the library's code typography is ignored here.
  val codeBlock: @Composable (String, String?, TextStyle) -> Unit = { text, language, _ ->
    CodeBox(
      modifier = Modifier.padding(vertical = PipkinTokens.Spacing.s6),
      label = language?.takeIf { it.isNotBlank() },
    ) { CodeText(text, language) }
  }
  val state = rememberParsedMarkdown(markdown, cache)
  SelectionContainer(modifier = modifier) {
    Markdown(
      state = state,
      modifier = Modifier.fillMaxWidth(),
      colors =
        markdownColor(
          text = colors.foreground,
          codeBackground = colors.surface2,
          inlineCodeBackground = colors.surface2,
          dividerColor = colors.border,
        ),
      typography = typography(body),
      padding = markdownPadding(block = PipkinTokens.Spacing.s6, codeBlock = PaddingValues(0.dp)),
      dimens = markdownDimens(codeBackgroundCornerSize = PipkinTokens.Radius.base),
      components =
        markdownComponents(
          codeFence = { MarkdownCodeFence(it.content, it.node, style = it.typography.code, block = codeBlock) },
          codeBlock = { MarkdownCodeBlock(it.content, it.node, style = it.typography.code, block = codeBlock) },
          table = { MarkdownTable(it.content, it.node, it.typography.table) },
        ),
      // The library animates each text block's size by default, which relayouts every frame a streamed
      // block grows and leaves the chat's end moving after the text has landed.
      animations = markdownAnimations(animateTextSize = { this }),
      annotator = imageAltAnnotator(),
    )
  }
}

/**
 * [markdown] parsed off the main thread, one parse at a time with the latest text. Until the current text's parse
 * lands the last result stays, so a streamed reply doesn't collapse to the empty loading box between deltas. A text
 * [cache] holds needs no parse, and each result is kept in [cache].
 */
@Composable
private fun rememberParsedMarkdown(markdown: String, cache: MarkdownCache?): State {
  var parsed by remember { mutableStateOf<State>(cache?.get(markdown) ?: State.Loading()) }
  val latest by rememberUpdatedState(markdown to cache)
  LaunchedEffect(Unit) {
    // Conflated, so text that changes faster than it parses skips to the latest instead of queueing every delta.
    snapshotFlow { latest }.conflate().collect { (text, latestCache) ->
      val shown = parsed
      val next = when {
        shown is State.Success && shown.content == text -> shown
        else -> latestCache?.get(text) ?: parseOffMain(text)
      }
      parsed = next
      latestCache?.put(text, next)
    }
  }
  return parsed
}

/**
 * Pipkin has no image loader, so an image shows as Paseo shows one that failed to load: its alt text, small and muted
 * on surface2, or nothing without alt. Left to the library, each image reserves an empty box the size of a picture.
 */
@Composable
private fun imageAltAnnotator(): MarkdownAnnotator {
  val colors = pipkinColors
  val style = SpanStyle(color = colors.foregroundMuted, background = colors.surface2, fontSize = IMAGE_ALT_FONT_SIZE)
  return remember(style) {
    markdownAnnotator { content, node ->
      if (node.type != MarkdownElementTypes.IMAGE) return@markdownAnnotator false
      val alt = node.resolveImageAlt(content)
      if (!alt.isNullOrBlank()) withStyle(style) { append(alt) }
      true
    }
  }
}

private val IMAGE_ALT_FONT_SIZE = 10.sp

@Composable
private fun typography(body: TextStyle): MarkdownTypography {
  val colors = pipkinColors
  val code = TextStyle(
    color = colors.foreground,
    fontFamily = PipkinFonts.mono,
    fontSize = PipkinTokens.FontSize.code,
    lineHeight = PipkinTokens.LineHeight.code,
    fontFeatureSettings = PipkinFonts.FEATURES,
  )
  return markdownTypography(
    h1 = body.copy(fontSize = PipkinTokens.FontSize.xxl, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    h2 = body.copy(fontSize = PipkinTokens.FontSize.xl, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    h3 = body.copy(fontSize = PipkinTokens.FontSize.lg, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    h4 = body.copy(fontWeight = FontWeight.SemiBold),
    h5 = body.copy(fontWeight = FontWeight.SemiBold),
    h6 = body.copy(fontWeight = FontWeight.SemiBold),
    text = body,
    code = code,
    inlineCode = code.copy(lineHeight = PipkinTokens.LineHeight.markdown),
    quote = body.copy(color = colors.foregroundMuted),
    paragraph = body,
    ordered = body,
    bullet = body,
    list = body,
    textLink = TextLinkStyles(style = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)),
    // The front matter table's cell type, a size under the body so more of a row fits across.
    table = body.copy(fontSize = PipkinTokens.FontSize.base, lineHeight = 20.sp),
    alertTitle = body.copy(fontWeight = FontWeight.SemiBold),
  )
}
