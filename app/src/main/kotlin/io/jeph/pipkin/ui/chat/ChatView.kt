package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.agent.AgentRegistry
import io.jeph.pipkin.agent.TimelineItem
import io.jeph.pipkin.ui.components.MarkdownCache
import java.util.WeakHashMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** More items than a screen holds, at the height of the shortest rows. */
private const val WARM_ITEMS = 30

/** More reply text than a screen holds. */
private const val WARM_CHARS = 4_000

/** The item at the top of the stream's viewport, by its list key, and how far it is scrolled past. */
internal data class StreamTop(val key: Any, val offset: Int)

/**
 * Where one chat's stream was left, so switching back to the chat shows it as it was: whether it follows the end and
 * the item at its top. Only the position is kept, not the list's [LazyListState], which holds on to the last layout
 * of the rows it showed.
 */
@Stable
internal class ChatScroll {
  /** Whether the stream keeps its end in view. Sending turns it back on. */
  val follow = mutableStateOf(true)

  /** Where the stream was when it last left the screen; null before then. */
  var top: StreamTop? = null
}

/**
 * What the chat screen keeps of one chat while another one shows: its [scroll] position, its replies' parsed
 * [markdown], so switching back draws the chat as it was from the first frame, and its [widgetTabs].
 */
internal class ChatView {
  val scroll = ChatScroll()
  val markdown = MarkdownCache()
  val widgetTabs = WidgetTabs()
}

/** Each chat's [ChatView], kept as long as its process: a parked chat keeps its own, a closed one's is dropped. */
internal class ChatViews {
  private val views = WeakHashMap<AgentProcess, ChatView>()

  operator fun get(process: AgentProcess): ChatView = views.getOrPut(process) { ChatView() }

  /**
   * Parses the finished replies at the end of every chat [registry] has open but no pane shows, as they finish or as a
   * reloaded history brings them, so switching to one of those chats draws its end from the first frame. Runs on the
   * main thread until cancelled; the parsing itself runs off it.
   */
  suspend fun warmHidden(registry: AgentRegistry<AgentProcess>) {
    combine(registry.processes, registry.shown) { processes, shown ->
      processes.values.filter { process -> shown.values.none { it === process } }
    }
      .distinctUntilChanged()
      .collectLatest { hidden ->
        coroutineScope {
          for (process in hidden) launch { warm(process) }
        }
      }
  }

  private suspend fun warm(process: AgentProcess) {
    val markdown = get(process).markdown
    process.screenTimeline
      .map { it.items.endReplies() }
      .distinctUntilChanged()
      .collect { markdown.warm(it) }
  }
}

/** The texts of the finished replies among the last [WARM_ITEMS] items, taken from the end until they reach [WARM_CHARS]. */
private fun List<TimelineItem>.endReplies(): Set<String> {
  val replies = HashSet<String>()
  var chars = 0
  for (item in asReversed().asSequence().take(WARM_ITEMS)) {
    if (chars >= WARM_CHARS) break
    if (item is TimelineItem.AssistantText && !item.streaming) {
      replies += item.markdown
      chars += item.markdown.length
    }
  }
  return replies
}
