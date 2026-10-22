package io.jeph.pipkin.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors

/**
 * The connection's status in a card that keeps its height through every state, with the check button inside it. A
 * failure opens a panel below that explains it, and stays while the user edits or checks again until a check
 * succeeds; it fades while it no longer describes the current details.
 */
@Composable
internal fun ConnectionSection(form: ProviderForm, onCheck: () -> Unit) {
  val failure = form.lastFailure
  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s6)) {
    FieldLabel(stringResource(R.string.provider_connection))
    SettingsSection(title = null, modifier = Modifier.animateContentSize()) {
      ConnectionStatusRow(form = form, onCheck = onCheck)
      if (failure != null) {
        SettingsDivider()
        ConnectionFailurePanel(
          failure = failure,
          modifier = Modifier.alpha(if (form.check === failure) 1f else OUTDATED_ALPHA),
        )
      }
    }
  }
}

@Composable
private fun ConnectionStatusRow(form: ProviderForm, onCheck: () -> Unit) {
  val colors = pipkinColors
  val check = form.check
  val status = connectionStatus(form)
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
        .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Box(modifier = Modifier.size(PipkinTokens.IconSize.lg), contentAlignment = Alignment.Center) {
      when (check) {
        is ConnectionCheck.Checking -> SimmeringPot()
        is ConnectionCheck.Ok -> StatusIcon(R.drawable.ic_lucide_circle_check, colors.dotSuccess)
        is ConnectionCheck.Failed -> StatusIcon(R.drawable.ic_lucide_circle_x, colors.destructive)
        ConnectionCheck.Idle -> StatusIcon(R.drawable.ic_lucide_circle, colors.foregroundExtraMuted)
      }
    }
    // One line each, so the row is the same height in every state.
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = status.title,
        color = if (check is ConnectionCheck.Failed) colors.destructive else colors.foreground,
        fontSize = PipkinTokens.FontSize.base,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = status.subtitle,
        color = colors.foregroundMuted,
        fontSize = PipkinTokens.FontSize.sm,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    SmallButton(label = stringResource(R.string.provider_check), onClick = onCheck, enabled = form.canCheck)
  }
}

private class ConnectionStatus(val title: String, val subtitle: String)

@Composable
private fun connectionStatus(form: ProviderForm): ConnectionStatus = when (val check = form.check) {
  is ConnectionCheck.Checking ->
    ConnectionStatus(stringResource(R.string.provider_status_checking), hostOf(form.baseUrl))

  is ConnectionCheck.Ok -> ConnectionStatus(
    stringResource(R.string.provider_status_connected),
    pluralStringResource(R.plurals.provider_models_available, check.models.size, check.models.size),
  )

  is ConnectionCheck.Failed -> ConnectionStatus(stringResource(R.string.provider_status_failed), hostOf(form.baseUrl))

  ConnectionCheck.Idle -> ConnectionStatus(
    stringResource(R.string.provider_status_unchecked),
    stringResource(
      when {
        !form.hasConnectionDetails -> R.string.provider_status_needs_details
        form.checkOutdated -> R.string.provider_status_outdated
        else -> R.string.provider_status_ready
      },
    ),
  )
}

@Composable
private fun StatusIcon(@DrawableRes icon: Int, tint: Color) {
  LucideIcon(icon = icon, contentDescription = null, size = PipkinTokens.IconSize.lg, tint = tint)
}

/** What went wrong in words, what to try, and what the server said, which can be selected to copy. */
@Composable
private fun ConnectionFailurePanel(failure: ConnectionCheck.Failed, modifier: Modifier = Modifier) {
  val explanation = failureExplanation(failure.code)
  ErrorPanel(
    title = stringResource(explanation.title),
    message = explanation.hint?.let { stringResource(it) } ?: failure.message,
    detail = failure.message.takeIf { explanation.hint != null && explanation.showDetail },
    modifier = modifier,
    standalone = false,
  )
}

private class FailureExplanation(@StringRes val title: Int, @StringRes val hint: Int?, val showDetail: Boolean = true)

/** Unknown codes and failures that never reached the host show the message as it came. */
private fun failureExplanation(code: String?): FailureExplanation = when (code) {
  "unauthorized" -> FailureExplanation(R.string.provider_error_unauthorized, R.string.provider_error_unauthorized_hint)
  "not_found" -> FailureExplanation(R.string.provider_error_not_found, R.string.provider_error_not_found_hint)
  "rate_limited" -> FailureExplanation(R.string.provider_error_rate_limited, R.string.provider_error_rate_limited_hint)
  "server_error" -> FailureExplanation(R.string.provider_error_server, R.string.provider_error_server_hint)
  "http_error" -> FailureExplanation(R.string.provider_error_http, R.string.provider_error_http_hint)
  "network" -> FailureExplanation(R.string.provider_error_network, R.string.provider_error_network_hint)
  "timeout" -> FailureExplanation(R.string.provider_error_timeout, R.string.provider_error_timeout_hint, false)
  "bad_url" -> FailureExplanation(R.string.provider_error_bad_url, R.string.provider_error_bad_url_hint, false)
  "bad_response" -> FailureExplanation(R.string.provider_error_bad_response, R.string.provider_error_bad_response_hint)
  else -> FailureExplanation(R.string.provider_error_unknown, null)
}

/** The URL's host for status lines, or the URL itself when it has none. */
private fun hostOf(url: String): String = url.trim().toUri().host?.takeIf { it.isNotEmpty() } ?: url.trim()

private const val OUTDATED_ALPHA = 0.5f
