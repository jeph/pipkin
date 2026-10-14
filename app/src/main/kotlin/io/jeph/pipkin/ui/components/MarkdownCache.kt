package io.jeph.pipkin.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parsed markdown by its text, kept across compositions, so markdown that leaves the screen and comes back shows parsed
 * from its first frame instead of empty until a new parse lands. Holds failed parses too: parsing the same text fails
 * the same way. Main-thread confined.
 */
@Stable
class MarkdownCache {
  private val parsed = HashMap<String, State>()

  /** Texts [warm] parsed that weren't shown since; only these does a later [warm] drop. */
  private val warmed = HashSet<String>()

  /** Changes whenever a parse is added, so whatever reads it in composition, layout or drawing runs again then. */
  var version by mutableIntStateOf(0)
    private set

  /** [markdown]'s finished parse, successful or not; null while it has none. */
  operator fun get(markdown: String): State? = parsed[markdown]

  fun put(markdown: String, state: State) {
    if (parsed.put(markdown, state) !== state) version++
  }

  /**
   * Parses [markdowns] before they show, for a chat out of sight. Texts an earlier call warmed that aren't in
   * [markdowns] are dropped, so a hidden chat that keeps replying holds the parses of its latest replies only.
   */
  suspend fun warm(markdowns: Set<String>) {
    val stale = warmed - markdowns
    stale.forEach(parsed::remove)
    warmed -= stale
    for (markdown in markdowns) {
      if (markdown in parsed) continue
      warmed += markdown
      put(markdown, parseOffMain(markdown))
    }
  }

  /**
   * Keeps only [markdowns], the texts a chat leaving the screen still has, as after a session switch it no longer has
   * the old ones. Everything kept counts as shown from then on, so [warm] leaves it.
   */
  fun keepShown(markdowns: Set<String>) {
    parsed.keys.retainAll(markdowns)
    warmed.clear()
  }
}

/** [markdown] parsed on a background thread. */
internal suspend fun parseOffMain(markdown: String): State =
  withContext(Dispatchers.Default) { parseMarkdown(markdown) }
