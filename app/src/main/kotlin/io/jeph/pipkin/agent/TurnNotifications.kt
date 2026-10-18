package io.jeph.pipkin.agent

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import io.jeph.pipkin.R

/**
 * Tells the user a chat's work ended while Pipkin was in the background, as a heads-up notification naming the chat,
 * its project and model, and the last run's reply. Work ends once the run and the background tasks it waits on are
 * over, so a run that leaves tasks running isn't announced. Tapping it opens that chat. Main thread only.
 */
internal class TurnNotifications(application: Application, private val home: () -> String?) {
  private val context: Context = application
  private val manager = context.getSystemService(NotificationManager::class.java)

  /**
   * Whether Pipkin's activity is on screen. ProcessLifecycleOwner reports leaving only 700ms later, which
   * would drop a run that ends just after the user switched away. A rotation stops and restarts the
   * activity, so a stop for a configuration change does not count as leaving.
   */
  private var onScreen = false

  init {
    // A channel's importance is fixed once created, so the high-importance channel has a new id.
    manager.deleteNotificationChannel(LEGACY_CHANNEL)
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL,
        context.getString(R.string.turn_notification_channel),
        NotificationManager.IMPORTANCE_HIGH,
      ),
    )
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
          onScreen = true
        }

        override fun onActivityStopped(activity: Activity) {
          if (!activity.isChangingConfigurations) onScreen = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
      },
    )
  }

  /** Posts [process]'s result, replacing the chat's earlier one; nothing while Pipkin is on screen. */
  fun workEnded(process: AgentProcess) {
    if (onScreen) return
    val processState = process.state.value
    val outcome = context.getString(
      when (processState) {
        is AgentProcessState.Exited -> R.string.turn_notification_exited
        is AgentProcessState.Failed -> R.string.turn_notification_failed
        else -> R.string.turn_notification_finished
      },
    )
    val text = (processState as? AgentProcessState.Failed)?.message ?: lastReply(process.timeline.state.value.items)
    val notification = ChatNotification.builder(context, CHANNEL, process, home().orEmpty())
      .setContentTitle(ChatNotification.title(context, process, outcome))
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .build()
    manager.notify(ChatNotification.tag(process), NOTIFICATION_ID, notification)
  }

  /** The run's last reply or error, looking back no further than the user's message that started it. */
  private fun lastReply(items: List<TimelineItem>): String? {
    for (item in items.asReversed()) {
      when (item) {
        is TimelineItem.AssistantText -> if (item.markdown.isNotBlank()) return item.markdown
        is TimelineItem.Error -> return item.cause.text(context.resources)
        is TimelineItem.User -> return null
        else -> Unit
      }
    }
    return null
  }

  companion object {
    private const val CHANNEL = "finished_chats"
    private const val LEGACY_CHANNEL = "turns"
    private const val NOTIFICATION_ID = 2
  }
}
