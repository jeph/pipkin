package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.ui.components.MarkdownCache
import java.util.WeakHashMap

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
 * What the chat screen keeps of one chat while another one shows: its [scroll] position and its replies' parsed
 * [markdown], so switching back draws the chat as it was from the first frame.
 */
internal class ChatView {
  val scroll = ChatScroll()
  val markdown = MarkdownCache()
}

/** Each chat's [ChatView], kept as long as its process: a parked chat keeps its own, a closed one's is dropped. */
internal class ChatViews {
  private val views = WeakHashMap<AgentProcess, ChatView>()

  operator fun get(process: AgentProcess): ChatView = views.getOrPut(process) { ChatView() }
}
