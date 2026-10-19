package io.jeph.pipkin.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.jeph.pipkin.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tells the user a chat waits on them in a dialog they can't see: one comes up in a chat that isn't on screen, because
 * Pipkin is in the background, another screen covers the panes, or the panes show other chats, terminals, files or
 * commits. It goes in the [inbox] always, and becomes a heads-up notification naming the chat and the dialog's question
 * while Pipkin is in the background, including once Pipkin leaves the screen with the dialog still waiting. Tapping the
 * notification opens the chat. Both go away once the chat is on screen or no dialog waits there any more. Works while
 * [watch] runs. Main thread only.
 */
internal class DialogNotifications(
  application: Application,
  registry: AgentRegistry<AgentProcess>,
  private val chatsOnScreen: StateFlow<Set<AgentProcess>>,
  private val visibility: AppVisibility,
  private val inbox: ChatInbox,
  private val home: () -> String?,
) {
  private val context: Context = application
  private val manager = context.getSystemService(NotificationManager::class.java)

  /** By chat, the tag its notification was posted under, since a new chat's session file can arrive after. */
  private val postedTags = HashMap<AgentProcess, String>()

  /** By chat, the dialog told only in the inbox while Pipkin was on screen, to notify once Pipkin leaves it. */
  private val toldInApp = HashMap<AgentProcess, ExtensionUiRequest>()
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
  }

  /** Follows the chats' dialogs, the chats on screen and Pipkin leaving the screen, until cancelled. */
  suspend fun watch(): Unit = coroutineScope {
    launch {
      chatDialogs.combine(chatsOnScreen, ::Pair).collect { (dialogs, onScreen) -> alerts.update(dialogs, onScreen) }
    }
    launch {
      visibility.onScreen.collect { onScreen ->
        if (onScreen) return@collect
        toldInApp.forEach { (process, dialog) -> notify(process, dialog) }
        toldInApp.clear()
      }
    }
  }

  private fun post(process: AgentProcess, dialog: ExtensionUiRequest) {
    ChatNotification.inboxEntry(process, InboxReason.Waiting, question(dialog))?.let(inbox::add)
    if (visibility.onScreen.value) toldInApp[process] = dialog else notify(process, dialog)
  }

  private fun notify(process: AgentProcess, dialog: ExtensionUiRequest) {
    val question = question(dialog).ifEmpty { context.getString(R.string.dialog_notification_text) }
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

  /** The dialog's title and message, each on its own line; empty for a dialog with neither. */
  private fun question(dialog: ExtensionUiRequest): String =
    listOfNotNull(dialog.title, dialog.message).filter { it.isNotBlank() }.joinToString("\n")

  private fun cancel(process: AgentProcess) {
    toldInApp.remove(process)
    process.sessionFile.value?.let(inbox::dialogGone)
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
