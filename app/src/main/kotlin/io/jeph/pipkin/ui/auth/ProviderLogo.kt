package io.jeph.pipkin.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/** Claude's brand orange; the other marks are monochrome, so they follow the theme's foreground. */
private val ClaudeOrange = Color(0xFFD97757)

/** The brand mark beside a provider Pi ships; nothing for other providers. */
@Composable
internal fun ProviderLogo(providerId: String, modifier: Modifier = Modifier) {
  val icon = when (providerId) {
    "anthropic" -> R.drawable.ic_brand_claude
    "openai-codex" -> R.drawable.ic_brand_openai
    "google" -> R.drawable.ic_brand_gemini
    "zai" -> R.drawable.ic_brand_zai
    "kimi-coding" -> R.drawable.ic_brand_kimi
    else -> return
  }
  LucideIcon(
    icon = icon,
    contentDescription = null,
    modifier = modifier,
    size = PipkinTokens.IconSize.lg,
    tint = if (providerId == "anthropic") ClaudeOrange else pipkinColors.foreground,
  )
}
