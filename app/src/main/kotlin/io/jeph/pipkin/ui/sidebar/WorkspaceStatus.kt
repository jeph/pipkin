package io.jeph.pipkin.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeph.pipkin.agent.AgentProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** What a workspace's chats are doing, in rising order of how much it needs the user. */
internal enum class WorkspaceActivity { Idle, Working, Waiting }

/** The line under a workspace's name: what its chats are doing, and how many [tabs] (chats and others) it has. */
internal data class WorkspaceStatus(val activity: WorkspaceActivity, val tabs: Int)

/**
 * What [processes] are doing, the most pressing first: a dialog Pi waits on the user for, then any run or background
 * task. Combined off the main thread, since a streaming chat's timeline changes many times a second.
 */
@Composable
internal fun workspaceActivity(processes: List<AgentProcess>): WorkspaceActivity {
  val activity = remember(processes) {
    combine(processes.map(::activityOf)) { states -> states.maxOrNull() ?: WorkspaceActivity.Idle }
      .distinctUntilChanged()
      .flowOn(Dispatchers.Default)
  }
  // Read now, so the row opens already saying what the chats do instead of catching up a frame later.
  val current = remember(processes) {
    processes.maxOfOrNull {
      activity(
        it.working.value,
        it.timeline.state.value.pendingUi != null,
      )
    }
  }
  return activity.collectAsStateWithLifecycle(initialValue = current ?: WorkspaceActivity.Idle).value
}

private fun activityOf(process: AgentProcess): Flow<WorkspaceActivity> = combine(
  process.working,
  process.timeline.state.map { it.pendingUi != null }.distinctUntilChanged(),
  ::activity,
)

private fun activity(working: Boolean, waiting: Boolean): WorkspaceActivity = when {
  waiting -> WorkspaceActivity.Waiting
  working -> WorkspaceActivity.Working
  else -> WorkspaceActivity.Idle
}
