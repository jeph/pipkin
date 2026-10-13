package io.jeph.pipkin.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import io.jeph.pipkin.agent.ImagePart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The steering box's contents, Edit and Send now. Pi's queue reports only texts, so this keeps what Pipkin sent
 * with each queued steer, the text as typed and its attachments, to show and bring back exactly that.
 */
internal class ChatSteering(private val controller: ChatController) {
  /**
   * The steers Pipkin sent, oldest first, by the text Pi queued them under; several can share a text, such as the
   * empty one of steers that only carry attachments. Snapshot state, since Pi reports the queue before it answers
   * the prompt and the box must catch up once a steer is noted.
   */
  private val sentSteers = mutableStateMapOf<String, List<SentSteer>>()

  /** Notes a steer Pi queued as [message]: the [typed] text and the [picked] attachments it carried. */
  fun record(message: String, typed: String, picked: List<Attachment>) {
    sentSteers[message] = sentSteers[message].orEmpty() + SentSteer(typed, picked)
  }

  /** Notes a steer taken back from Pi that Pi queued again. */
  fun record(steer: TakenSteer) = record(steer.message, steer.sent.typed, steer.sent.picked)

  /** The box's view of [queue]: each message as typed, one per line, and how many attachments came with them. */
  fun summary(queue: List<String>): SteeringSummary {
    val steers = matchSteers(queue, sentSteers)
    return SteeringSummary(typedText(steers), steers.sumOf { it.picked.size })
  }

  /** Forgets the steers Pi delivered or dropped; the screen runs it for as long as it shows. */
  suspend fun collectQueue() {
    controller.process.timeline.state.map { it.queue }.distinctUntilChanged().collect { queue ->
      val counts = queue.groupingBy { it }.eachCount()
      for ((text, steers) in sentSteers.toMap()) {
        // Pi delivers the oldest first, so the ones still queued are the newest.
        val live = steers.takeLast(counts[text] ?: 0)
        when {
          live.isEmpty() -> sentSteers.remove(text)
          live.size < steers.size -> sentSteers[text] = live
        }
      }
    }
  }

  /** Takes the steering messages back from Pi into the composer, one per line, so Pi never delivers them. */
  fun edit() {
    controller.scope.launch {
      val steers = take().ifEmpty { return@launch }
      controller.restoreDraft(typedText(steers.map { it.sent }), steers.flatMap { it.sent.picked })
    }
  }

  /**
   * Stops the run and sends the queued steers Pi has not delivered yet, each as its own message. Pi has no
   * command for this, so the steers are taken back with `clear_queue` first, then Pi aborts and gets them again.
   */
  fun sendNow() {
    if (controller.sending) return
    controller.launchSending(command = false) {
      val steers = take().ifEmpty { return@launchSending }
      val failure = controller.interruptWith(steers) ?: return@launchSending
      // Steers queued before the failure would otherwise reach Pi's next run as well as the composer.
      if (steers.size > 1) controller.query { clearQueue() }
      controller.restoreDraft(typedText(steers.map { it.sent }), steers.flatMap { it.sent.picked }, failure)
    }
  }

  /**
   * Empties Pi's queue; empty when Pi delivered all of it or did not answer. Pi may have taken the queue for its
   * next turn before clearing it and still delivers what it took, so this waits for that turn's reply and leaves
   * out every message Pi started meanwhile.
   */
  private suspend fun take(): List<TakenSteer> {
    val timeline = controller.process.timeline
    // clear_queue reports the emptied queue before it answers, which prunes the map before the answer arrives.
    val sent = sentSteers.toMap()
    val mark = timeline.startedUserMessages.mark()
    val texts = controller.query { clearQueue() } ?: return emptyList()
    sentSteers.clear()
    timeline.state.first { !it.queueInFlight }
    val kept = texts.toMutableList()
    // Pi delivers the oldest of equal texts first, so the ones left are the newest, as matchSteers assumes.
    timeline.startedUserMessages.textsSince(mark).forEach(kept::remove)
    return kept.zip(matchSteers(kept, sent), ::TakenSteer)
  }
}

/**
 * Pairs each message of Pi's [queue] with the steer Pipkin [sent] it as. Of the steers sent under one text, the
 * newest are the ones still queued: older ones were delivered, even when Pi answered their prompt only after
 * reporting that. A queued message Pipkin did not send, from an extension, counts as typed with no attachments.
 */
internal fun matchSteers(queue: List<String>, sent: Map<String, List<SentSteer>>): List<SentSteer> {
  val counts = queue.groupingBy { it }.eachCount()
  val seen = HashMap<String, Int>()
  return queue.map { text ->
    val index = seen.getOrDefault(text, 0)
    seen[text] = index + 1
    val count = counts.getValue(text)
    val live = sent[text].orEmpty().takeLast(count)
    live.getOrNull(index - (count - live.size)) ?: SentSteer(text, emptyList())
  }
}

/** The [steers] joined one per line as typed, leaving out the file paths Pi's text lists for their attachments. */
private fun typedText(steers: List<SentSteer>): String =
  steers.map { it.typed }.filter { it.isNotBlank() }.joinToString("\n")

/** What the steering box shows: the queued messages as typed, and how many attachments they carry. */
internal class SteeringSummary(val text: String, val attachments: Int)

/** A queued steer as Pipkin sent it; Pi's text adds the paths of [picked]'s files to [typed]. */
internal class SentSteer(val typed: String, val picked: List<Attachment>)

/** A steer taken back from Pi: the [message] Pi had queued, which lists any files' paths, and what Pipkin [sent]. */
internal class TakenSteer(val message: String, val sent: SentSteer) {
  val images: List<ImagePart> get() = sent.picked.filterIsInstance<Attachment.Image>().map { it.part }
}
