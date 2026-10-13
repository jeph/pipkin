package io.jeph.pipkin.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The steering box's contents, Edit and Send now. Pi's queue reports only texts, so this keeps what Pipkin sent
 * with each queued steer: the text as typed, its images to bring back with it, and how many files it listed.
 */
internal class ChatSteering(private val controller: ChatController) {
  /**
   * What went into each steer Pi still queues that carried attachments, by the text Pi queued it under. Snapshot
   * state, since Pi reports the queue before it answers the prompt and the box must catch up once this is noted.
   */
  private val sentSteers = mutableStateMapOf<String, SentSteer>()

  /** Notes a steer Pi queued as [message]: the [typed] text, its [images] and how many [files] it listed. */
  fun record(message: String, typed: String, images: List<Attachment.Image>, files: Int) {
    if (images.isNotEmpty() || files > 0) sentSteers[message] = SentSteer(typed, images, files)
  }

  /** The box's view of [queue]: each message as typed, one per line, and how many attachments came with them. */
  fun summary(queue: List<String>): SteeringSummary {
    val sent = queue.map { sentSteers[it] }
    val text = queue.zip(sent) { message, steer -> steer?.typed ?: message }.filter { it.isNotBlank() }
    return SteeringSummary(text.joinToString("\n"), sent.sumOf { it?.attachments ?: 0 })
  }

  /** Forgets the steers Pi delivered or dropped; the screen runs it for as long as it shows. */
  suspend fun collectQueue() {
    controller.process.timeline.state.map { it.queue }.distinctUntilChanged().collect { queue ->
      sentSteers.keys.retainAll(queue.toSet())
    }
  }

  /** Takes the steering message back from Pi into the composer, so Pi never delivers it. */
  fun edit() {
    controller.scope.launch {
      val steer = take() ?: return@launch
      controller.restoreDraft(steer.text, steer.images)
    }
  }

  /**
   * Stops the run and sends the steering message as the next prompt, unless Pi delivered it meanwhile. Pi has no
   * command for this, so the steer is taken back with `clear_queue` first, then Pi aborts and is prompted again.
   */
  fun sendNow() {
    if (controller.sending) return
    controller.launchSending(command = false) {
      val steer = take() ?: return@launchSending
      val failure = controller.deliver(steer.text, steer.images, interrupt = true) ?: return@launchSending
      controller.restoreDraft(steer.text, steer.images, failure)
    }
  }

  /**
   * Empties Pi's queue into one newline-joined message; null when Pi delivered all of it or did not answer. Pi may
   * have taken the queue for its next turn before clearing it and still delivers what it took, so this waits for
   * that turn's reply and leaves out every message Pi started meanwhile.
   */
  private suspend fun take(): QueuedSteer? {
    val timeline = controller.process.timeline
    // clear_queue reports the emptied queue before it answers, which prunes the map before the answer arrives.
    val sent = sentSteers.toMap()
    val mark = timeline.startedUserMessages.mark()
    val texts = controller.query { clearQueue() } ?: return null
    sentSteers.clear()
    timeline.state.first { !it.queueInFlight }
    val kept = texts.toMutableList()
    timeline.startedUserMessages.textsSince(mark).forEach(kept::remove)
    if (kept.isEmpty()) return null
    return QueuedSteer(kept.joinToString("\n"), kept.flatMap { sent[it]?.images.orEmpty() })
  }
}

/** What the steering box shows: the queued messages as typed, and how many attachments they carry. */
internal class SteeringSummary(val text: String, val attachments: Int)

/** A queued steer as Pipkin sent it; Pi's text adds the [files]' paths to [typed]. */
private class SentSteer(val typed: String, val images: List<Attachment.Image>, val files: Int) {
  val attachments get() = images.size + files
}

/** Pi's queued steers, joined into one message, with the images Pipkin sent along. */
private class QueuedSteer(val text: String, val images: List<Attachment.Image>)
