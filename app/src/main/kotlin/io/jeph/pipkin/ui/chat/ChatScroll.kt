package io.jeph.pipkin.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import io.jeph.pipkin.agent.AgentProcess
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

/** Each chat's [ChatScroll], kept as long as its process: a parked chat keeps its own, a closed one's is dropped. */
internal class ChatScrolls {
  private val scrolls = WeakHashMap<AgentProcess, ChatScroll>()

  operator fun get(process: AgentProcess): ChatScroll = scrolls.getOrPut(process) { ChatScroll() }
}
