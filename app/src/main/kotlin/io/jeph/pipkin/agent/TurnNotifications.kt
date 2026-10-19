package io.jeph.pipkin.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.jeph.pipkin.R

/**
 * Tells the user a chat's work ended while the chat was off screen: in the [inbox] always, and while Pipkin is in the
 * background also as a heads-up notification naming the chat, its project and model, and the last run's reply. Work
 * ends once the run and the background tasks it waits on are over, so a run that leaves tasks running isn't announced.
 * Tapping the notification opens that chat. Main thread only.
 */
internal class TurnNotifications(
  application: Application,
  private val visibility: AppVisibility,
  private val inbox: ChatInbox,
  private val home: () -> String?,
) {
  private val context: Context = application
  private val manager = context.getSystemService(NotificationManager::class.java)

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
  }

  /** Tells of [process]'s result, replacing the chat's earlier one. */
  fun workEnded(process: AgentProcess) {
    val processState = process.state.value
    val text = (processState as? AgentProcessState.Failed)?.message ?: lastReply(process.timeline.state.value.items)
    val reason = when (processState) {
      is AgentProcessState.Exited -> InboxReason.Exited
      is AgentProcessState.Failed -> InboxReason.Failed
      else -> InboxReason.Finished
    }
    ChatNotification.inboxEntry(process, reason, text)?.let(inbox::add)
    if (visibility.onScreen.value) return
    val outcome = context.getString(
      when (reason) {
        InboxReason.Exited -> R.string.turn_notification_exited
        InboxReason.Failed -> R.string.turn_notification_failed
        else -> R.string.turn_notification_finished
      },
    )
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
