package io.jeph.pipkin.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.components.LucideIcon
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.components.PillTone
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pi's provider for the Claude subscription. */
internal const val CLAUDE_PROVIDER = "anthropic"

/**
 * pi-anthropic-auth's npm package. It shapes Claude subscription requests like Claude Code's, which Anthropic bills
 * against the plan instead of as extra usage.
 */
internal const val CLAUDE_PLUGIN_PACKAGE = "@gotgenes/pi-anthropic-auth"
private const val CLAUDE_PLUGIN_URL = "https://github.com/gotgenes/pi-anthropic-auth"
private const val PROGRESS_MAX_LINES = 2

private sealed interface PluginStep {
  data object Offered : PluginStep

  /** [line] is the newest line `pi install` printed, if any. */
  data class Installing(val line: String?) : PluginStep

  data object Installed : PluginStep

  /** [message] is null when the host gave no reason. */
  data class Failed(val message: String?) : PluginStep
}

/**
 * Offered once Claude sign-in succeeds: what pi-anthropic-auth does and risks, with Skip and Install. The install runs
 * while the offer is shown; dismissing the sheet aborts it. [onDone] closes the sheet after Skip or a finished install.
 */
@Composable
internal fun ClaudePluginOffer(onOpen: (String) -> Unit, onDone: () -> Unit) {
  val accounts = LocalContext.current.appGraph.accountRepository
  val colors = pipkinColors
  val scope = rememberCoroutineScope()
  val currentOnDone by rememberUpdatedState(onDone)
  var step by remember { mutableStateOf<PluginStep>(PluginStep.Offered) }
  val install: () -> Unit = {
    step = PluginStep.Installing(null)
    scope.launch {
      step = try {
        accounts.installPackage("npm:$CLAUDE_PLUGIN_PACKAGE") { line ->
          if (line.isNotBlank()) step = PluginStep.Installing(line)
        }
        PluginStep.Installed
      } catch (failure: IOException) {
        PluginStep.Failed(failure.message)
      } catch (rejected: HostRejected) {
        PluginStep.Failed(rejected.error)
      }
    }
  }
  LaunchedEffect(step) {
    if (step != PluginStep.Installed) return@LaunchedEffect
    delay(1_000)
    currentOnDone()
  }

  Column(verticalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    LeadingRow(text = stringResource(R.string.claude_plugin_warning)) {
      LucideIcon(icon = R.drawable.ic_lucide_triangle_alert, contentDescription = null, tint = colors.dotWarning)
    }
    Text(
      text = stringResource(R.string.claude_plugin_explanation),
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.base,
    )
    TextButton(onClick = { onOpen(CLAUDE_PLUGIN_URL) }) { Text(stringResource(R.string.claude_plugin_link)) }
    PluginStepContent(step = step, onSkip = onDone, onInstall = install)
  }
}

@Composable
private fun PluginStepContent(step: PluginStep, onSkip: () -> Unit, onInstall: () -> Unit) {
  val colors = pipkinColors
  when (step) {
    PluginStep.Offered -> PluginActions(onSkip = onSkip, onInstall = onInstall)

    is PluginStep.Installing -> {
      LeadingRow(text = stringResource(R.string.claude_plugin_installing), color = colors.foregroundMuted) {
        SimmeringPot()
      }
      step.line?.let {
        Text(
          text = it,
          color = colors.foregroundExtraMuted,
          fontSize = PipkinTokens.FontSize.base,
          maxLines = PROGRESS_MAX_LINES,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    PluginStep.Installed -> LeadingRow(text = stringResource(R.string.claude_plugin_installed)) {
      LucideIcon(icon = R.drawable.ic_lucide_check, contentDescription = null, tint = colors.accent)
    }

    is PluginStep.Failed -> {
      LeadingRow(text = step.message ?: stringResource(R.string.claude_plugin_failed)) {
        LucideIcon(icon = R.drawable.ic_lucide_circle_alert, contentDescription = null, tint = colors.destructive)
      }
      PluginActions(onSkip = onSkip, onInstall = onInstall)
    }
  }
}

@Composable
private fun PluginActions(onSkip: () -> Unit, onInstall: () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s8)) {
    PillButton(label = stringResource(R.string.claude_plugin_skip), onClick = onSkip, tone = PillTone.Tonal)
    PillButton(label = stringResource(R.string.claude_plugin_install), onClick = onInstall)
  }
}
