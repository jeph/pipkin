package io.jeph.pipkin.ui.components

import androidx.compose.runtime.Stable
import com.mikepenz.markdown.model.State

/**
 * Parsed markdown by its text, kept across compositions, so markdown that leaves the screen and comes back shows parsed
 * from its first frame instead of empty until a new parse lands. Main-thread confined.
 */
@Stable
class MarkdownCache {
  private val parsed = HashMap<String, State.Success>()

  operator fun get(markdown: String): State.Success? = parsed[markdown]

  fun put(state: State.Success) {
    parsed[state.content] = state
  }

  /** Forgets every text but [markdowns], for example once they are all a chat still shows. */
  fun retainOnly(markdowns: Set<String>) {
    parsed.keys.retainAll(markdowns)
  }
}
