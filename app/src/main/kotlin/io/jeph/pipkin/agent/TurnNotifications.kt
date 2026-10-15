package io.jeph.pipkin.agent

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.jeph.pipkin.MainActivity
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.sidebar.projectName

/** A chat a notification opens: its project folder and session file. */
internal data class ChatLocation(val cwd: String, val sessionPath: String)

/**
 * Tells the user a chat's run ended while Pipkin was in the background, as a heads-up notification naming the chat,
 * its project and model, and the run's last reply. Tapping it opens that chat. Main thread only.
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
  fun runEnded(process: AgentProcess) {
    if (onScreen) return
    val timeline = process.timeline.state.value
    val chatTitle = process.sessionName.value?.takeIf { it.isNotBlank() }
      ?: timeline.items.firstNotNullOfOrNull { (it as? TimelineItem.User)?.text?.takeIf(String::isNotBlank) }
    val processState = process.state.value
    val outcome = context.getString(
      when (processState) {
        is AgentProcessState.Exited -> R.string.turn_notification_exited
        is AgentProcessState.Failed -> R.string.turn_notification_failed
        else -> R.string.turn_notification_finished
      },
    )
    val title = chatTitle?.let { context.getString(R.string.turn_notification_title, outcome, it) } ?: outcome
    val text = (processState as? AgentProcessState.Failed)?.message ?: lastReply(timeline.items)
    val project = projectName(process.cwd, home().orEmpty())
    val model = timeline.model?.optString("id")?.takeIf { it.isNotBlank() }
    val sessionPath = process.sessionFile.value
    val open = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    sessionPath?.let { open.putExtra(EXTRA_CWD, process.cwd).putExtra(EXTRA_SESSION, it) }
    // Pi names the session file before a run ends, so the tag and request code are one per chat.
    val tag = sessionPath ?: process.cwd
    val notification = Notification.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(title)
      .setContentText(text)
      .setSubText(listOfNotNull(project, model).joinToString(" · "))
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setContentIntent(
        PendingIntent.getActivity(
          context,
          tag.hashCode(),
          open,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
      )
      .setAutoCancel(true)
      .build()
    manager.notify(tag, NOTIFICATION_ID, notification)
  }

  /** The run's last reply or error, looking back no further than the user's message that started it. */
  private fun lastReply(items: List<TimelineItem>): String? {
    for (item in items.asReversed()) {
      when (item) {
        is TimelineItem.AssistantText -> if (item.markdown.isNotBlank()) return item.markdown
        is TimelineItem.Error -> return item.message
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
    private const val EXTRA_CWD = "io.jeph.pipkin.extra.CWD"
    private const val EXTRA_SESSION = "io.jeph.pipkin.extra.SESSION"

    /** The chat a tapped notification asks to open, or null for any other launch. */
    fun chatLocation(intent: Intent): ChatLocation? {
      val cwd = intent.getStringExtra(EXTRA_CWD) ?: return null
      val sessionPath = intent.getStringExtra(EXTRA_SESSION) ?: return null
      return ChatLocation(cwd, sessionPath)
    }
  }
}
