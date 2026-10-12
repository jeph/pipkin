package io.jeph.pipkin.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Assistant markdown styled to Paseo's message tokens: 15/21sp body, bordered horizontally scrolling code. */
@Composable
fun PipkinMarkdown(markdown: String, modifier: Modifier = Modifier) {
  val colors = pipkinColors
  val body = TextStyle(
    color = colors.foreground,
    fontSize = PipkinTokens.FontSize.content,
    lineHeight = PipkinTokens.LineHeight.markdown,
  )
  // CodeText is Pipkin's one code style, so the library's code typography is ignored here.
  val codeBlock: @Composable (String, String?, TextStyle) -> Unit = { text, language, _ ->
    CodeBox(modifier = Modifier.padding(vertical = PipkinTokens.Spacing.s6)) { CodeText(text, language) }
  }
  // Parsing runs off the main thread; without retaining, each streamed delta would show the empty
  // loading box until the new parse lands, collapsing and re-growing the message.
  val state = rememberMarkdownState(markdown, retainState = true)
  SelectionContainer(modifier = modifier) {
    Markdown(
      markdownState = state,
      modifier = Modifier.fillMaxWidth(),
      colors =
        markdownColor(
          text = colors.foreground,
          codeBackground = colors.surface2,
          inlineCodeBackground = colors.surface2,
          dividerColor = colors.border,
          tableBackground = colors.surface1,
        ),
      typography = typography(body),
      padding = markdownPadding(block = PipkinTokens.Spacing.s6, codeBlock = PaddingValues(0.dp)),
      dimens = markdownDimens(codeBackgroundCornerSize = PipkinTokens.Radius.md),
      components =
        markdownComponents(
          codeFence = { MarkdownCodeFence(it.content, it.node, style = it.typography.code, block = codeBlock) },
          codeBlock = { MarkdownCodeBlock(it.content, it.node, style = it.typography.code, block = codeBlock) },
        ),
      // The library animates each text block's size by default, which relayouts every frame a streamed
      // block grows and leaves the chat's end moving after the text has landed.
      animations = markdownAnimations(animateTextSize = { this }),
    )
  }
}

@Composable
private fun typography(body: TextStyle): MarkdownTypography {
  val colors = pipkinColors
  val code = TextStyle(
    color = colors.foreground,
    fontFamily = FontFamily.Monospace,
    fontSize = PipkinTokens.FontSize.code,
    lineHeight = PipkinTokens.LineHeight.code,
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
    table = body,
    alertTitle = body.copy(fontWeight = FontWeight.SemiBold),
  )
}
