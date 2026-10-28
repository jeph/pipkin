package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import io.jeph.pipkin.R
import io.jeph.pipkin.host.AuthStatus

/**
 * A provider Pi ships that Pipkin connects: a subscription signed in to with OAuth, or, when
 * [usesApiKey], one Pi stores an API key for. [api] is the API its models use.
 */
internal data class Subscription(
  val id: String,
  @StringRes val title: Int,
  @StringRes val subtitle: Int,
  @StringRes val loginTitle: Int,
  val api: String,
  val usesApiKey: Boolean = false,
)

internal val subscriptions = listOf(
  Subscription(
    "anthropic",
    R.string.settings_claude,
    R.string.settings_claude_subtitle,
    R.string.login_title_claude,
    "anthropic-messages",
  ),
  Subscription(
    "openai-codex",
    R.string.settings_openai,
    R.string.settings_openai_subtitle,
    R.string.login_title_openai,
    "openai-codex-responses",
  ),
  Subscription(
    "google",
    R.string.settings_gemini,
    R.string.settings_gemini_subtitle,
    R.string.login_title_gemini,
    "google-generative-ai",
    usesApiKey = true,
  ),
  Subscription(
    "zai",
    R.string.settings_zai,
    R.string.settings_zai_subtitle,
    R.string.login_title_zai,
    "openai-completions",
    usesApiKey = true,
  ),
  Subscription(
    "kimi-coding",
    R.string.settings_kimi,
    R.string.settings_kimi_subtitle,
    R.string.login_title_kimi,
    "openai-completions",
    usesApiKey = true,
  ),
  Subscription(
    "openrouter",
    R.string.settings_openrouter,
    R.string.settings_openrouter_subtitle,
    R.string.login_title_openrouter,
    "openai-completions",
    usesApiKey = true,
  ),
)

/** True once Pi holds a credential for the provider [id]. */
internal fun AuthStatus.isSignedIn(id: String): Boolean = builtin.any { it.id == id && it.configured }

/** True for a provider Pipkin connects with an API key, whose models the user picks after adding it. */
internal fun usesApiKey(providerId: String): Boolean = subscriptions.any { it.id == providerId && it.usesApiKey }
