package io.jeph.pipkin.ui.chat

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The steering box's Edit and Send now. Pi's queue reports only texts, so this keeps the images Pipkin sent with
 * each queued steer to bring them back along with the text.
 */
internal class ChatSteering(private val controller: ChatController) {
  /** Images sent with steers Pi still queues, by the text Pi queued them under. */
  private val queuedImages = mutableMapOf<String, List<Attachment.Image>>()

  /** Notes the [images] sent with a steer Pi queued as [message]. */
  fun record(message: String, images: List<Attachment.Image>) {
    if (images.isNotEmpty()) queuedImages[message] = images
  }

  /** Forgets the images of steers Pi delivered or dropped; the screen runs it for as long as it shows. */
  suspend fun collectQueue() {
    controller.process.timeline.state.map { it.queue }.distinctUntilChanged().collect { queue ->
      queuedImages.keys.retainAll(queue.toSet())
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
    val images = queuedImages.toMap()
    val mark = timeline.startedUserMessages.mark()
    val texts = controller.query { clearQueue() } ?: return null
    queuedImages.clear()
    timeline.state.first { !it.queueInFlight }
    val kept = texts.toMutableList()
    timeline.startedUserMessages.textsSince(mark).forEach(kept::remove)
    if (kept.isEmpty()) return null
    return QueuedSteer(kept.joinToString("\n"), kept.flatMap { images[it].orEmpty() })
  }
}

/** Pi's queued steers, joined into the one message the steering box shows, with the images Pipkin sent along. */
private class QueuedSteer(val text: String, val images: List<Attachment.Image>)
