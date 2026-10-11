package io.jeph.pipkin.ui.settings

import androidx.annotation.StringRes
import io.jeph.pipkin.R
import io.jeph.pipkin.host.AuthStatus

/** A subscription Pipkin signs in to with OAuth. */
internal data class Subscription(val id: String, @StringRes val title: Int, @StringRes val subtitle: Int)

internal val subscriptions = listOf(
  Subscription("anthropic", R.string.settings_claude, R.string.settings_claude_subtitle),
  Subscription("openai-codex", R.string.settings_openai, R.string.settings_openai_subtitle),
)

/** True once Pi holds a credential for the subscription [id]. */
internal fun AuthStatus.isSignedIn(id: String): Boolean = builtin.any { it.id == id && it.configured }
