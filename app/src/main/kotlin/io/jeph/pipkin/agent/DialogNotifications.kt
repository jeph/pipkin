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
 * while Pipkin is in the background, including once Pipkin leaves the screen with the dialog still waiting. Both name
 * the subagent that asked when Pipkin can tell, and take its name in without alerting again when it's known only later.
 * Tapping the notification opens the chat. Both go away once the chat is on screen or no dialog waits there any more.
 * Works while [watch] runs. Main thread only.
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
  private val toldInApp = HashMap<AgentProcess, FrontDialog>()
  private val alerts = DialogAlerts(post = ::post, retell = ::retell, cancel = ::cancel)

  /** Every open chat's front dialog and who asked it, null for a chat that waits on none. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val chatDialogs = registry.processes.flatMapLatest { processes ->
    val chats = processes.values
    if (chats.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(chats.map { chat -> frontDialog(chat).map { chat to it }.distinctUntilChanged() }) { it.toMap() }
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
        toldInApp.forEach { (process, dialog) -> notify(process, dialog, alertAgain = true) }
        toldInApp.clear()
      }
    }
  }

  /** [chat]'s front dialog, with the asking subagent worked out again as its runs and tool calls change. */
  private fun frontDialog(chat: AgentProcess) = combine(chat.timeline.state, chat.subagentRuns) { timeline, runs ->
    timeline.pendingUi?.let { request ->
      val asker = (askingSubagent(timeline, runs) as? SubagentAsker.One)?.run?.agent
      FrontDialog(request, asker?.takeIf { it.isNotBlank() })
    }
  }

  private fun post(process: AgentProcess, dialog: FrontDialog) {
    ChatNotification.inboxEntry(process, InboxReason.Waiting, question(dialog.request), dialog.asker)?.let(inbox::add)
    if (visibility.onScreen.value) toldInApp[process] = dialog else notify(process, dialog, alertAgain = true)
  }

  /**
   * Names the subagent that asked [process]'s told dialog, known only now: in its inbox entry, and in its notification
   * if that's still showing, posted again under its tag without a second alert.
   */
  private fun retell(process: AgentProcess, dialog: FrontDialog) {
    val asker = dialog.asker ?: return
    process.sessionFile.value?.let { inbox.nameAsker(it, asker) }
    if (toldInApp.containsKey(process)) {
      toldInApp[process] = dialog
      return
    }
    val tag = postedTags[process] ?: return
    // Posting again would bring back a notification the user swiped away.
    if (manager.activeNotifications.none { it.tag == tag && it.id == NOTIFICATION_ID }) return
    notify(process, dialog, alertAgain = false)
  }

  /** Posts [dialog]'s notification; one posted again under the same tag only alerts when [alertAgain]. */
  private fun notify(process: AgentProcess, dialog: FrontDialog, alertAgain: Boolean) {
    val question = question(dialog.request).ifEmpty { context.getString(R.string.dialog_notification_text) }
    val headline = dialog.asker?.let { context.getString(R.string.dialog_notification_waiting_subagent, it) }
      ?: context.getString(R.string.dialog_notification_waiting)
    val notification = ChatNotification.builder(context, CHANNEL, process, home().orEmpty())
      .setContentTitle(ChatNotification.title(context, process, headline))
      .setContentText(question)
      .setStyle(Notification.BigTextStyle().bigText(question))
      .setOnlyAlertOnce(!alertAgain)
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
 * The dialog at the front of a chat's queue, with the agent name of the subagent that asked it when no one else could
 * have; see [askingSubagent].
 */
internal data class FrontDialog(val request: ExtensionUiRequest, val asker: String?)

/**
 * Decides when to tell the user about a chat's dialog, through [post], and when to take that back, through [cancel].
 * The dialog at the front of each chat's queue, the one its screen shows, is told once, when it reaches the front
 * while the chat is off screen; one the user saw on screen is not told after they leave. A told dialog is taken back
 * once its chat is on screen, or its chat no longer waits on a dialog or has closed. When the subagent that asked a
 * told dialog is named only later, or another one is, the dialog is told again through [retell].
 */
internal class DialogAlerts<C : Any>(
  private val post: (C, FrontDialog) -> Unit,
  private val retell: (C, FrontDialog) -> Unit,
  private val cancel: (C) -> Unit,
) {
  /** By chat, the id of its front dialog that the user saw or was told about. */
  private val handled = HashMap<C, String>()

  /** By chat whose dialog was told, the subagent it was told as asked by, null for none. */
  private val posted = HashMap<C, String?>()

  /** Takes in every open chat's front dialog, null when it waits on none, and the chats on screen. */
  fun update(dialogs: Map<C, FrontDialog?>, onScreen: Set<C>) {
    val takenBack = posted.keys.filter { dialogs[it] == null || it in onScreen }
    posted -= takenBack.toSet()
    takenBack.forEach(cancel)
    handled.keys.retainAll { dialogs[it] != null }
    for ((chat, dialog) in dialogs) dialog?.let { take(chat, it, chat in onScreen) }
  }

  /** Tells [chat]'s front [dialog] if it's new there and the chat is off screen, or retells it once its asker is named. */
  private fun take(chat: C, dialog: FrontDialog, onScreen: Boolean) {
    val id = dialog.request.id
    if (handled.put(chat, id) == id) return renamed(chat, dialog)
    // A new front dialog counts as handled even on screen, so leaving its chat afterwards tells nothing.
    if (onScreen) return
    posted[chat] = dialog.asker
    post(chat, dialog)
  }

  /**
   * Retells [chat]'s told dialog once it names a subagent it wasn't told with. Losing the name doesn't: the call that
   * asked stays in flight until the dialog is answered, so the name only drops because other calls started since.
   */
  private fun renamed(chat: C, dialog: FrontDialog) {
    val asker = dialog.asker ?: return
    if (!posted.containsKey(chat) || posted[chat] == asker) return
    posted[chat] = asker
    retell(chat, dialog)
  }
}
