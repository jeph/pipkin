package io.jeph.pipkin.agent

import io.jeph.pipkin.agent.TimelineItem.Steps

/** How the run a [Steps] fold belongs to went on after it: still working, done, or ended by Stop. */
enum class StepsOutcome {
  Working,
  Done,
  Stopped,
}

/** A thinking block or a tool call the agent made; a `!command` the user ran is not one. */
val TimelineItem.isAgentStep: Boolean
  get() = this is TimelineItem.Thinking || (this is TimelineItem.ToolCall && !byUser)

/**
 * A timeline's items as the stream shows them: [entries] has each run of two or more steps folded into one [Steps],
 * which a run of other items ends. [groupAt] is each fold's index in [entries], by key.
 */
class StepStream private constructor(val entries: List<TimelineItem>, private val groupAt: Map<String, Int>) {
  /** [entries] with the steps of each fold keyed in [expanded] listed under it. */
  fun expand(expanded: Set<String>): StreamRows {
    val folds = expanded.mapNotNull(groupAt::get).sorted()
    if (folds.isEmpty()) return StreamRows(entries, emptyMap())
    val rows = ArrayList<TimelineItem>(entries.size + folds.sumOf { (entries[it] as Steps).steps.size })
    val listed = HashMap<String, Boolean>()
    var from = 0
    for (index in folds) {
      val steps = (entries[index] as Steps).steps
      rows.addAll(entries.subList(from, index + 1))
      rows.addAll(steps)
      steps.forEachIndexed { position, step -> listed[step.key] = position == steps.lastIndex }
      from = index + 1
    }
    rows.addAll(entries.subList(from, entries.size))
    return StreamRows(rows, listed)
  }

  /** The key of the fold holding the step keyed [key], or null when no fold does. */
  fun foldHolding(key: String): String? =
    entries.firstOrNull { entry -> entry is Steps && entry.steps.any { it.key == key } }?.key

  companion object {
    /** Folds [items], a timeline snapshot that is never changed afterwards, since the folds share it. */
    fun of(items: List<TimelineItem>): StepStream {
      val entries = ArrayList<TimelineItem>(items.size)
      val groupAt = HashMap<String, Int>()
      var start = 0
      while (start < items.size) {
        var end = start
        while (end < items.size && items[end].isAgentStep) end++
        when (end - start) {
          0, 1 -> entries += items[start++]

          else -> {
            val fold = fold(items.subList(start, end), items.getOrNull(end))
            groupAt[fold.key] = entries.size
            entries += fold
            start = end
          }
        }
      }
      return StepStream(entries, groupAt)
    }

    /** The fold of [steps], which [next] follows: the run's footer while it works or once it ended, if nothing else. */
    private fun fold(steps: List<TimelineItem>, next: TimelineItem?): Steps {
      val outcome = when {
        next !is TimelineItem.TurnFooter -> StepsOutcome.Done
        next.durationMillis == null -> StepsOutcome.Working
        next.stopped -> StepsOutcome.Stopped
        else -> StepsOutcome.Done
      }
      return Steps(
        key = "steps:${steps.first().key}",
        steps = steps,
        outcome = outcome,
        current = steps.lastOrNull { it.running } ?: steps.last(),
      )
    }

    private val TimelineItem.running: Boolean
      get() = (this is TimelineItem.ToolCall && status != ToolStatus.Done) ||
        (this is TimelineItem.Thinking && streaming)
  }
}

/** The rows a stream lists: [items], among them the steps of expanded folds, which [listed] holds. */
class StreamRows(val items: List<TimelineItem>, private val listed: Map<String, Boolean>) {
  /** Whether the row keyed [key] is a step listed under its fold. */
  fun isListedStep(key: String): Boolean = key in listed

  /** Whether the row keyed [key] is the last step listed under its fold. */
  fun isLastListedStep(key: String): Boolean = listed[key] == true
}
