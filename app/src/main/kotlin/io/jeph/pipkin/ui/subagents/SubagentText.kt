package io.jeph.pipkin.ui.subagents

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.SubagentRun
import io.jeph.pipkin.ui.settings.formatTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay

/** The phase as the Subagents list and the subagent screen name it. */
@get:StringRes
internal val SubagentPhase.label: Int
  get() = when (this) {
    SubagentPhase.Queued -> R.string.subagent_status_queued
    SubagentPhase.Running -> R.string.subagent_status_running
    SubagentPhase.Asking -> R.string.subagent_status_asking
    SubagentPhase.Done -> R.string.subagent_status_done
    SubagentPhase.Failed -> R.string.subagent_status_failed
    SubagentPhase.Stopped -> R.string.subagent_status_stopped
    SubagentPhase.Interrupted -> R.string.subagent_status_interrupted
  }

/** The phase as the end of a tool row names it, in lower case; null for a running one, whose row shows its time. */
@get:StringRes
internal val SubagentPhase.rowLabel: Int?
  get() = when (this) {
    SubagentPhase.Queued -> R.string.subagent_row_queued
    SubagentPhase.Running -> null
    SubagentPhase.Asking -> R.string.subagent_row_asking
    SubagentPhase.Done -> R.string.subagent_row_done
    SubagentPhase.Failed -> R.string.subagent_row_failed
    SubagentPhase.Stopped -> R.string.subagent_row_stopped
    SubagentPhase.Interrupted -> R.string.subagent_row_interrupted
  }

/** Cobalt while it works, amber while it waits on the user, green once done, red once failed, muted otherwise. */
@Composable
internal fun phaseColor(phase: SubagentPhase): Color {
  val colors = pipkinColors
  return when (phase) {
    SubagentPhase.Running -> colors.accent
    SubagentPhase.Asking -> colors.dotWarning
    SubagentPhase.Done -> colors.dotSuccess
    SubagentPhase.Failed -> colors.destructive
    SubagentPhase.Queued, SubagentPhase.Stopped, SubagentPhase.Interrupted -> colors.foregroundMuted
  }
}

/** A time span as `0:42` or `1:02:03`, with the locale's digits. */
internal fun elapsedText(millis: Long): String = DateUtils.formatElapsedTime((millis / 1_000L).coerceAtLeast(0))

/**
 * How long [run] has run: up to now while it runs, ticking each second, or until it ended; null before it started,
 * as while it's queued.
 */
@Composable
internal fun runTime(run: SubagentRun): String? {
  val start = run.startedAt ?: return null
  val end = run.endedAt
  if (end != null) return elapsedText(end - start)
  val now by produceState(System.currentTimeMillis(), start) {
    while (true) {
      value = System.currentTimeMillis()
      delay(1_000L - (value - start).mod(1_000L))
    }
  }
  return elapsedText(now - start)
}

/** "12.4K tokens", shortened with the locale's digits. */
@Composable
internal fun tokensText(tokens: Long): String = stringResource(R.string.subagents_tokens, formatTokens(tokens))
