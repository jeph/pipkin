package io.jeph.pipkin.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.jeph.pipkin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tells the user a chat waits on them in a dialog they can't see, as a heads-up notification naming the chat and the
 * dialog's question: one comes up in a chat that isn't on screen, because Pipkin is in the background, another screen
 * covers the panes, or the panes show other chats, terminals, files or commits. Tapping it opens the chat. It goes
 * away once the chat is on screen or no dialog waits there any more. Main thread only.
 */
internal class DialogNotifications(
  application: Application,
  registry: AgentRegistry<AgentProcess>,
  scope: CoroutineScope,
  private val home: () -> String?,
) {
  private val context: Context = application
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val chatsOnScreen = MutableStateFlow<Set<AgentProcess>>(emptySet())

  /** By chat, the tag its notification was posted under, since a new chat's session file can arrive after. */
  private val postedTags = HashMap<AgentProcess, String>()
  private val alerts = DialogAlerts(post = ::post, cancel = ::cancel)

  /** Every open chat's front dialog, null for one that waits on none. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val chatDialogs = registry.processes.flatMapLatest { processes ->
    val chats = processes.values
    if (chats.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(chats.map { chat -> chat.timeline.state.map { chat to it.pendingUi }.distinctUntilChanged() }) {
        it.toMap()
      }
    }
  }

  init {
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL,
        context.getString(R.string.dialog_notification_channel),
        NotificationManager.IMPORTANCE_HIGH,
      ),
    )
    scope.launch {
      chatDialogs.combine(chatsOnScreen, ::Pair).collect { (dialogs, onScreen) -> alerts.update(dialogs, onScreen) }
    }
  }

  /** Sets the chats the user sees: those the panes show while Pipkin is on screen and nothing covers them. */
  fun setChatsOnScreen(chats: Set<AgentProcess>) {
    chatsOnScreen.value = chats
  }

  private fun post(process: AgentProcess, dialog: ExtensionUiRequest) {
    val question = listOfNotNull(dialog.title, dialog.message).filter { it.isNotBlank() }.joinToString("\n")
      .ifEmpty { context.getString(R.string.dialog_notification_text) }
    val headline = context.getString(R.string.dialog_notification_waiting)
    val notification = ChatNotification.builder(context, CHANNEL, process, home().orEmpty())
      .setContentTitle(ChatNotification.title(context, process, headline))
      .setContentText(question)
      .setStyle(Notification.BigTextStyle().bigText(question))
      .build()
    val tag = ChatNotification.tag(process)
    postedTags.put(process, tag)?.takeIf { it != tag }?.let { manager.cancel(it, NOTIFICATION_ID) }
    manager.notify(tag, NOTIFICATION_ID, notification)
  }

  private fun cancel(process: AgentProcess) {
    postedTags.remove(process)?.let { manager.cancel(it, NOTIFICATION_ID) }
  }

  private companion object {
    const val CHANNEL = "chat_dialogs"
    const val NOTIFICATION_ID = 3
  }
}

/**
 * Decides when to tell the user about a chat's dialog, through [post], and when to take that back, through [cancel].
 * The dialog at the front of each chat's queue, the one its screen shows, is told once, when it reaches the front
 * while the chat is off screen; one the user saw on screen is not told after they leave. A told dialog is taken back
 * once its chat is on screen, or its chat no longer waits on a dialog or has closed.
 */
internal class DialogAlerts<C : Any>(
  private val post: (C, ExtensionUiRequest) -> Unit,
  private val cancel: (C) -> Unit,
) {
  /** By chat, the id of its front dialog that the user saw or was told about. */
  private val handled = HashMap<C, String>()
  private val posted = HashSet<C>()

  /** Takes in every open chat's front dialog, null when it waits on none, and the chats on screen. */
  fun update(dialogs: Map<C, ExtensionUiRequest?>, onScreen: Set<C>) {
    val takenBack = posted.filter { dialogs[it] == null || it in onScreen }
    posted -= takenBack.toSet()
    takenBack.forEach(cancel)
    handled.keys.retainAll { dialogs[it] != null }
    for ((chat, dialog) in dialogs) {
      // A new front dialog counts as handled even on screen, so leaving its chat afterwards tells nothing.
      if (dialog == null || handled.put(chat, dialog.id) == dialog.id || chat in onScreen) continue
      posted += chat
      post(chat, dialog)
    }
  }
}
