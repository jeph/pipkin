package io.jeph.pipkin.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Claude's brand orange; the OpenAI mark is monochrome, so it follows the theme's foreground. */
private val ClaudeOrange = Color(0xFFD97757)

/** The brand mark beside a subscription's sign-in option; nothing for other providers. */
@Composable
internal fun ProviderLogo(providerId: String, modifier: Modifier = Modifier) {
  when (providerId) {
    "anthropic" ->
      LucideIcon(
        icon = R.drawable.ic_brand_claude,
        contentDescription = null,
        modifier = modifier,
        size = PipkinTokens.IconSize.lg,
        tint = ClaudeOrange,
      )

    "openai-codex" ->
      LucideIcon(
        icon = R.drawable.ic_brand_openai,
        contentDescription = null,
        modifier = modifier,
        size = PipkinTokens.IconSize.lg,
        tint = pipkinColors.foreground,
      )
  }
}
