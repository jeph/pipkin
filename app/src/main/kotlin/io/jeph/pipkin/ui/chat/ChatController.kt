package io.jeph.pipkin.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.RpcError
import io.jeph.pipkin.agent.TimelineEffect
import io.jeph.pipkin.agent.UiAnswer
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.components.SnackbarStackState
import io.jeph.pipkin.ui.sidebar.tildePath
import java.io.IOException
import java.lang.ref.Reference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the header's ellipsis sheet can do. [onCopyPath] and [onArchive] are null until Pi reported a session file. */
internal class ChatActions(
  val onNewChat: () -> Unit,
  val onNewTerminal: () -> Unit,
  val onRename: () -> Unit,
  val onReload: () -> Unit,
  val onCopyPath: (() -> Unit)?,
  val onArchive: (() -> Unit)?,
)

/**
 * The chat screen's draft and actions for one [process]. Runs work on the screen's [scope] and reports
 * failures as snackbars, so a closed screen cancels its pending calls.
 */
internal class ChatController(
  val process: AgentProcess,
  private val graph: AppGraph,
  private val context: Context,
  val scope: CoroutineScope,
  private val snackbar: SnackbarStackState,
  navigator: ChatNavigator,
) {
  val draft = TextFieldState()

  /** Whether the composer's text field has focus; the slash menu only shows while it does. */
  var composerFocused by mutableStateOf(false)

  /** Whether the context meter's tooltip shows; any press in the chat closes it. */
  var contextDetailsOpen by mutableStateOf(false)

  val slash = SlashMenu(this)

  val builtins = BuiltinCommands(this, navigator)

  val steering = ChatSteering(this)

  /** Images and files for the next prompt, in pick order. */
  val attachments = mutableStateListOf<Attachment>()

  /** True from a send until Pi answered its `prompt`. */
  var sending by mutableStateOf(false)
    private set

  /** Whether [sending] a slash command, which Pi answers only once the command finished. */
  var sendingCommand by mutableStateOf(false)
    private set

  /** Stop was pressed before Pi answered the prompt being sent, so the run that prompt starts is aborted. */
  private var stopRequested = false

  /** Whether the stream keeps its end in view. Sending turns it back on. */
  val follow = mutableStateOf(true)

  val canSend: Boolean get() = (draft.text.isNotBlank() || attachments.isNotEmpty()) && !photosOnlySteer

  /**
   * Whether sending now would steer the run with photos and no text. Pi (1.1.0, still in 1.2.0) takes a delivered
   * steer off the queue it reports only when the steer has text (`if (messageText)` in `AgentSession`'s
   * `message_start` handling), so a photo-only steer would stay in the steering box after Pi delivered it.
   * Revisit once Pi fixes that: remove this check when the pinned Pi clears steers with empty text.
   */
  val photosOnlySteer: Boolean
    get() = process.busy.value && draft.text.isBlank() && attachments.isNotEmpty() &&
      attachments.all { it is Attachment.Image }

  fun setDraftText(text: String) {
    draft.setTextAndPlaceCursorAtEnd(text)
  }

  /**
   * Sends the draft; Pi steers a working run with it. A built-in command such as `/compact` runs in
   * Pipkin instead. Returns false when nothing was sent, as while Pi starts: the history shown then is only a copy,
   * and the prompt or most built-in commands would reach no Pi.
   */
  fun send(): Boolean {
    if (!canSend || process.state.value.starting) return false
    val builtin = parseBuiltin(draft.text.toString()).takeIf { attachments.isEmpty() }
    if (builtin != null) {
      draft.clearText()
      builtins.run(builtin)
      return true
    }
    return sendPrompt()
  }

  /**
   * Files are first copied into Termux and listed by path, since Pi's `prompt` only carries images. A
   * failed send puts the draft back.
   */
  private fun sendPrompt(): Boolean {
    if (sending) return false
    val picked = attachments.toList()
    // Picking checks the limit, but a failed send puts its files back beside any picked meanwhile.
    if (picked.filterIsInstance<Attachment.File>().sumOf { it.bytes.size.toLong() } > ChatFiles.MAX_TOTAL_BYTES) {
      show(R.string.chat_files_too_large)
      return false
    }
    val text = draft.text.toString()
    draft.clearText()
    attachments.clear()
    launchSending(command = text.trim().startsWith("/")) {
      deliver(text, picked)?.let { failure -> restoreDraft(text, picked, failure) }
    }
    return true
  }

  /** Runs [block] with [sending] set from now on, so a second send waits and Stop applies to it. */
  fun launchSending(command: Boolean, block: suspend () -> Unit) {
    sending = true
    sendingCommand = command
    stopRequested = false
    follow.value = true
    scope.launch {
      try {
        block()
      } finally {
        sending = false
        sendingCommand = false
      }
    }
  }

  /** Prompts Pi with [text] and [picked]; returns why that failed, if it did. */
  private suspend fun deliver(text: String, picked: List<Attachment>): String? = failureOf {
    val files = picked.filterIsInstance<Attachment.File>().map { it.name to it.bytes }
    val paths = if (files.isEmpty()) emptyList() else graph.hostClient.saveAttachments(files)
    val typed = text.trim()
    val message = withFilePaths(typed, paths)
    val disposition = process.prompt(message, picked.filterIsInstance<Attachment.Image>().map { it.part })
    if (disposition == "queued") steering.record(message, typed, picked)
    abortIfStopped(disposition)
  }

  /**
   * Stops the run and sends [steers] as the messages they were queued as, one each, as Pi delivers steers itself:
   * Pi takes the steers queued when a run starts into its first turn, so all but the first are queued before
   * the first is prompted. Returns why that failed, if it did.
   */
  suspend fun interruptWith(steers: List<TakenSteer>): String? = failureOf {
    process.abort()
    for (steer in steers.drop(1)) {
      if (process.steer(steer.message, steer.images) == "queued") steering.record(steer)
    }
    val first = steers.first()
    val disposition = process.prompt(first.message, first.images)
    if (disposition == "queued") steering.record(first)
    abortIfStopped(disposition)
  }

  /** Pi starts the run just after answering, so an abort for a Stop pressed while sending reaches it now. */
  private fun abortIfStopped(disposition: String) {
    if (stopRequested && disposition == "started") launchRpc { process.abort() }
  }

  private suspend fun failureOf(block: suspend () -> Unit): String? = try {
    block()
    null
  } catch (error: RpcError) {
    error.error
  } catch (error: HostRejected) {
    error.error
  } catch (error: IOException) {
    error.message.orEmpty()
  }

  /**
   * Puts [text] and [picked] back in the composer ahead of anything typed or picked since, as the field stays
   * editable while sending, and shows [failure] when there is one.
   */
  fun restoreDraft(text: String, picked: List<Attachment>, failure: String? = null) {
    val typed = draft.text
    val restored = if (typed.isBlank()) text else "$text\n$typed"
    draft.setTextAndPlaceCursorAtEnd(restored)
    attachments.addAll(0, picked)
    failure?.let(::show)
  }

  /** [text] followed by the saved files' paths, `~`-relative like the rest of the app shows them. */
  private fun withFilePaths(text: String, paths: List<String>): String {
    if (paths.isEmpty()) return text
    val home = home
    val list = paths.joinToString("\n") { path -> "- " + (home?.let { tildePath(path, it) } ?: path) }
    val block = "$ATTACHED_FILES\n$list"
    return if (text.isEmpty()) block else "$text\n\n$block"
  }

  /** The Termux home from the last host hello, for `~`-relative paths. */
  val home: String? get() = graph.hostClient.lastHello.value?.home?.takeIf { it.isNotEmpty() }

  fun copy(text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pipkin", text))
  }

  /** Stops the run; pressed while a prompt is sending, it also stops the run that prompt starts. */
  fun abort() {
    if (sending) stopRequested = true
    launchRpc { process.abort() }
  }

  fun rename(name: String) = launchRpc { process.setSessionName(name.trim()) }

  /** Switches this chat's model and makes it the one new chats start with. */
  fun setModel(provider: String, modelId: String) = launchRpc {
    process.setModel(provider, modelId)
    saveDefault { graph.modelDefaults.saveModel(provider, modelId, context.getString(R.string.provider_failed)) }
  }

  /** Switches this chat's thinking level and makes it the one new chats start with. */
  fun setThinkingLevel(level: String) = launchRpc {
    process.setThinkingLevel(level)
    saveDefault { graph.modelDefaults.saveThinkingLevel(level, context.getString(R.string.provider_failed)) }
  }

  /** Saves on the app scope, so closing the chat right after picking still keeps the pick. */
  private fun saveDefault(save: suspend () -> String?) {
    graph.scope.launch { save()?.let(::show) }
  }

  fun respondUi(id: String, answer: UiAnswer) {
    scope.launch { process.respondUi(id, answer) }
  }

  /** Runs [block] against Pi; null (with a snackbar) when Pi rejected it or is not running. */
  suspend fun <T> query(block: suspend AgentProcess.() -> T): T? = try {
    process.block()
  } catch (error: RpcError) {
    show(error.error)
    null
  }

  /**
   * Reads [uris] into the attachments. [grant] is kept reachable until they are read: a keyboard's read permission
   * on its image lasts only while the InputContentInfo inside the content it sent is.
   */
  fun addImages(uris: List<Uri>, grant: Any? = null) {
    scope.launch {
      var failed = false
      for (uri in uris) {
        val attachment = ChatImages.attach(context, uri)
        if (attachment == null) failed = true else attachments += attachment
      }
      if (failed) show(R.string.chat_image_failed)
      Reference.reachabilityFence(grant)
    }
  }

  /** Reads picked documents, refusing any that would push the message past [ChatFiles.MAX_TOTAL_BYTES]. */
  fun addFiles(uris: List<Uri>) {
    scope.launch {
      var failed = false
      var tooLarge = false
      for (uri in uris) {
        val used = attachments.filterIsInstance<Attachment.File>().sumOf { it.bytes.size.toLong() }
        try {
          val file = ChatFiles.attach(context, uri, ChatFiles.MAX_TOTAL_BYTES - used)
          if (file == null) failed = true else attachments += file
        } catch (_: AttachmentsTooLarge) {
          tooLarge = true
        }
      }
      if (failed) show(R.string.chat_file_failed)
      if (tooLarge) show(R.string.chat_files_too_large)
    }
  }

  /**
   * Stops a working run first, then replaces Pi with a fresh process on the same session. Runs on the
   * app scope: the swap replaces the visible process, which disposes this screen's scope.
   */
  fun reload() {
    graph.scope.launch {
      if (process.busy.value) {
        try {
          process.abort()
        } catch (_: RpcError) {
          // Reloading ends the process anyway; a failed abort only means the run was already over.
        }
      }
      graph.registry.reload(process)
    }
  }

  /** The single collector of the timeline's one-shot extension effects. */
  suspend fun collectEffects() {
    process.timeline.effects.collect { effect ->
      when (effect) {
        is TimelineEffect.Notify -> show(effect.message)
        is TimelineEffect.SetEditorText -> setDraftText(effect.text)
      }
    }
  }

  fun show(@StringRes message: Int, vararg args: Any) = show(context.getString(message, *args))

  fun show(message: String) {
    if (message.isBlank()) return
    // show suspends until the snackbar leaves; a separate coroutine keeps callers and effects flowing.
    scope.launch { snackbar.show(message) }
  }

  /** Runs [block] on the screen's scope, showing an [RpcError] as a snackbar. */
  fun launchRpc(block: suspend () -> Unit) {
    scope.launch { query { block() } }
  }
}

private const val ATTACHED_FILES = "Attached files:"
