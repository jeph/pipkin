package io.jeph.pipkin.ui.chat

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.RpcError
import io.jeph.pipkin.agent.TimelineEffect
import io.jeph.pipkin.agent.UiAnswer
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.ui.sidebar.tildePath
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the header's ellipsis sheet can do. [onCopyPath] is null until Pi reported a session file. */
internal class ChatActions(
  val onNewChat: () -> Unit,
  val onRename: () -> Unit,
  val onReload: () -> Unit,
  val onCopyPath: (() -> Unit)?,
  val onDelete: () -> Unit,
)

/**
 * The chat screen's draft and actions for one [process]. Runs work on the screen's [scope] and reports
 * failures as snackbars, so a closed screen cancels its pending calls.
 */
internal class ChatController(
  val process: AgentProcess,
  private val graph: AppGraph,
  private val context: Context,
  private val scope: CoroutineScope,
  private val snackbar: SnackbarHostState,
) {
  var draft by mutableStateOf(TextFieldValue())

  /** Images and files for the next prompt, in pick order. */
  val attachments = mutableStateListOf<Attachment>()

  /** True between sending `prompt` and Pi's response. */
  var sending by mutableStateOf(false)
    private set

  val canSend: Boolean get() = draft.text.isNotBlank() || attachments.isNotEmpty()

  fun setDraftText(text: String) {
    draft = TextFieldValue(text, TextRange(text.length))
  }

  /**
   * Sends the draft; Pi steers a working run with it. Files are first copied into Termux and listed
   * by path, since Pi's `prompt` only carries images. A failed send puts the draft back.
   */
  fun send() {
    if (sending || !canSend) return
    val picked = attachments.toList()
    // Picking checks the limit, but a failed send puts its files back beside any picked meanwhile.
    if (picked.filterIsInstance<Attachment.File>().sumOf { it.bytes.size.toLong() } > ChatFiles.MAX_TOTAL_BYTES) {
      show(R.string.chat_files_too_large)
      return
    }
    val sentDraft = draft
    draft = TextFieldValue()
    attachments.clear()
    sending = true
    scope.launch {
      val failure = try {
        val files = picked.filterIsInstance<Attachment.File>().map { it.name to it.bytes }
        val paths = if (files.isEmpty()) emptyList() else graph.hostClient.saveAttachments(files)
        process.prompt(
          withFilePaths(sentDraft.text.trim(), paths),
          picked.filterIsInstance<Attachment.Image>().map {
            it.part
          },
        )
        null
      } catch (error: RpcError) {
        error.error
      } catch (error: HostRejected) {
        error.error
      } catch (error: IOException) {
        error.message.orEmpty()
      } finally {
        sending = false
      }
      if (failure != null) {
        // The field stayed editable while sending: put the failed text before anything typed since.
        val typed = draft.text
        val restored = if (typed.isBlank()) sentDraft.text else "${sentDraft.text}\n$typed"
        draft = TextFieldValue(restored, TextRange(restored.length))
        attachments.addAll(0, picked)
        show(failure)
      }
    }
  }

  /** [text] followed by the saved files' paths, `~`-relative like the rest of the app shows them. */
  private fun withFilePaths(text: String, paths: List<String>): String {
    if (paths.isEmpty()) return text
    val home = graph.hostClient.lastHello.value?.home?.takeIf { it.isNotEmpty() }
    val list = paths.joinToString("\n") { path -> "- " + (home?.let { tildePath(path, it) } ?: path) }
    val block = "$ATTACHED_FILES\n$list"
    return if (text.isEmpty()) block else "$text\n\n$block"
  }

  fun abort() = launchRpc { process.abort() }

  fun clearQueue() = launchRpc { process.clearQueue() }

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

  fun addImages(uris: List<Uri>) {
    scope.launch {
      var failed = false
      for (uri in uris) {
        val attachment = ChatImages.attach(context, uri)
        if (attachment == null) failed = true else attachments += attachment
      }
      if (failed) show(R.string.chat_image_failed)
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

  /**
   * Ends Pi, deletes the session file and hands over to [onDeleted], which opens another chat. Runs on
   * the app scope, since closing the process clears the visible chat and disposes this screen's scope.
   */
  fun delete(onDeleted: () -> Unit) {
    val path = process.sessionFile.value
    graph.registry.close(process)
    graph.scope.launch {
      val failure = if (path != null) deleteSession(path) else null
      onDeleted()
      failure?.let { show(it) }
    }
  }

  /** Deletes [path]; returns the user-facing failure message, or null. */
  private suspend fun deleteSession(path: String): String? = try {
    graph.sessionRepository.delete(path)
    null
  } catch (error: IOException) {
    error.message.orEmpty()
  } catch (error: HostRejected) {
    // Pi writes the session file with its first reply, so a chat without one has nothing to delete.
    error.error.takeUnless { error.code == "not_found" }
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

  fun show(@StringRes message: Int) = show(context.getString(message))

  fun show(message: String) {
    if (message.isBlank()) return
    // showSnackbar suspends until dismissed; a separate coroutine keeps callers and effects flowing.
    scope.launch { snackbar.showSnackbar(message) }
  }

  private fun launchRpc(block: suspend () -> Unit) {
    scope.launch { query { block() } }
  }
}

private const val ATTACHED_FILES = "Attached files:"
