package io.jeph.pipkin.ui.chat

import android.icu.text.NumberFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.localmodel.SessionActivity
import io.jeph.pipkin.localmodel.catalog.LocalModelCatalog
import io.jeph.pipkin.ui.components.LoadingPill
import io.jeph.pipkin.ui.theme.PipkinTokens
import org.json.JSONObject

/**
 * What the phone's model is doing for this chat, in a pill over the composer, while the chat's [model] is a local one
 * and its request waits or runs. A local model can take a while to load or to read a long prompt before the first word
 * shows, and other chats may hold it, so without this the chat would look stuck.
 */
@Composable
internal fun LocalModelActivityPill(process: AgentProcess, model: JSONObject?) {
  if (model?.optString("provider") != LocalModelCatalog.PROVIDER_ID) return
  val runtime = LocalContext.current.appGraph.localModels.runtime
  val sessionId by process.sessionId.collectAsStateWithLifecycle()
  val sessions by runtime.sessions.collectAsStateWithLifecycle()
  val activity = sessionId?.let(sessions::get) ?: return
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    LoadingPill(
      text = activityText(activity),
      // Announced as it changes, since it's the chat's only sign of progress until the reply starts.
      modifier = Modifier
        .padding(bottom = PipkinTokens.Spacing.s8)
        .semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}

@Composable
private fun activityText(activity: SessionActivity): String {
  val locale = LocalConfiguration.current.locales[0]
  val count = NumberFormat.getIntegerInstance(locale)
  return when (activity) {
    is SessionActivity.Queued -> {
      val waitingFor = activity.waitingFor
      val name = LocalModelCatalog.name(waitingFor ?: activity.modelId)
      val ahead = count.format(activity.position)
      when {
        waitingFor == null && activity.position == 0 -> stringResource(R.string.chat_local_queued, name)
        waitingFor == null -> pluralStringResource(R.plurals.chat_local_queued_behind, activity.position, name, ahead)
        activity.position == 0 -> stringResource(R.string.chat_local_busy, name)
        else -> pluralStringResource(R.plurals.chat_local_busy_behind, activity.position, name, ahead)
      }
    }

    is SessionActivity.Loading -> stringResource(
      R.string.chat_local_loading,
      LocalModelCatalog.name(activity.modelId),
      NumberFormat.getPercentInstance(locale).format(activity.progress),
    )

    is SessionActivity.ReadingPrompt -> if (activity.cached > 0) {
      stringResource(
        R.string.chat_local_reading_cached,
        count.format(activity.processed),
        count.format(activity.total),
        count.format(activity.cached),
      )
    } else {
      stringResource(R.string.chat_local_reading, count.format(activity.processed), count.format(activity.total))
    }

    is SessionActivity.Writing -> {
      val rate = NumberFormat.getInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
      }
      stringResource(R.string.chat_local_writing, rate.format(activity.tokensPerSecond))
    }
  }
}
