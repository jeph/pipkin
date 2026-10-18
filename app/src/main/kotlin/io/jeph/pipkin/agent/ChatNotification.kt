package io.jeph.pipkin.agent

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.jeph.pipkin.MainActivity
import io.jeph.pipkin.R
import io.jeph.pipkin.ui.sidebar.projectName

/** A chat a notification opens: its project folder and session file. */
internal data class ChatLocation(val cwd: String, val sessionPath: String)

/** What Pipkin's notifications about one chat share: their tag, title and look, and the chat a tap opens. */
internal object ChatNotification {
  private const val EXTRA_CWD = "io.jeph.pipkin.extra.CWD"
  private const val EXTRA_SESSION = "io.jeph.pipkin.extra.SESSION"

  /**
   * The tag of [process]'s notifications, one per chat: its session file, which Pi names before a dialog comes up or
   * a run ends, else its folder.
   */
  fun tag(process: AgentProcess): String = process.sessionFile.value ?: process.cwd

  /** "[headline]: chat title", from the chat's name or else its first message; just [headline] for a chat with neither. */
  fun title(context: Context, process: AgentProcess, headline: String): String {
    val chatTitle = process.sessionName.value?.takeIf { it.isNotBlank() }
      ?: process.timeline.state.value.items.firstNotNullOfOrNull {
        (it as? TimelineItem.User)?.text?.takeIf(String::isNotBlank)
      }
    return chatTitle?.let { context.getString(R.string.chat_notification_title, headline, it) } ?: headline
  }

  /**
   * A notification on [channel] about [process], with its project and model under the title, that opens the chat
   * when tapped and then goes away.
   */
  fun builder(context: Context, channel: String, process: AgentProcess, home: String): Notification.Builder {
    val project = projectName(process.cwd, home)
    val model = process.timeline.state.value.model?.optString("id")?.takeIf { it.isNotBlank() }
    val open = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    process.sessionFile.value?.let { open.putExtra(EXTRA_CWD, process.cwd).putExtra(EXTRA_SESSION, it) }
    return Notification.Builder(context, channel)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setSubText(listOfNotNull(project, model).joinToString(" · "))
      .setContentIntent(
        PendingIntent.getActivity(
          context,
          tag(process).hashCode(),
          open,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
      )
      .setAutoCancel(true)
  }

  /** The chat a tapped notification asks to open, or null for any other launch. */
  fun location(intent: Intent): ChatLocation? {
    val cwd = intent.getStringExtra(EXTRA_CWD) ?: return null
    val sessionPath = intent.getStringExtra(EXTRA_SESSION) ?: return null
    return ChatLocation(cwd, sessionPath)
  }
}
