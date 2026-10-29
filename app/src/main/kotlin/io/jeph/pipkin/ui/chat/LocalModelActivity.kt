package io.jeph.pipkin.ui.chat

import android.icu.text.NumberFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.localmodel.SessionActivity
import io.jeph.pipkin.localmodel.catalog.LocalModelCatalog
import org.json.JSONObject

/**
 * What the phone's model is doing for this chat, for its run footer, while the chat's [model] is a local one and its
 * request waits or runs; null otherwise. A local model can take a while to load or to read a long prompt before the
 * first word shows, and other chats may hold it, so without this the chat would look stuck.
 */
@Composable
internal fun localModelActivity(process: AgentProcess, model: JSONObject?): String? {
  if (model?.optString("provider") != LocalModelCatalog.PROVIDER_ID) return null
  val runtime = LocalContext.current.appGraph.localModels.runtime
  val sessionId by process.sessionId.collectAsStateWithLifecycle()
  val sessions by runtime.sessions.collectAsStateWithLifecycle()
  return sessionId?.let(sessions::get)?.let { activityText(it) }
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

    // LiteRT-LM doesn't say how far it has read, so its prompt's size stays unknown.
    is SessionActivity.ReadingPrompt -> when {
      activity.total == 0 -> stringResource(R.string.chat_local_reading_unknown)

      activity.cached > 0 -> stringResource(
        R.string.chat_local_reading_cached,
        count.format(activity.processed),
        count.format(activity.total),
        count.format(activity.cached),
      )

      else -> stringResource(
        R.string.chat_local_reading,
        count.format(activity.processed),
        count.format(activity.total),
      )
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
