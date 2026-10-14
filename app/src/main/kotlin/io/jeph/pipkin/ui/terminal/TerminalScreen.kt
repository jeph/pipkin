package io.jeph.pipkin.ui.terminal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sagernet.libghostty.GhosttyTerminalView
import io.github.sagernet.libghostty.GhosttyTheme
import io.github.sagernet.libghostty.compose.GhosttyDialogs
import io.github.sagernet.libghostty.compose.GhosttyTerminal
import io.github.sagernet.libghostty.compose.rememberGhosttyTerminalState
import io.jeph.pipkin.R
import io.jeph.pipkin.agent.AgentProcess
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.terminal.TerminalState
import io.jeph.pipkin.ui.chat.MenuButton
import io.jeph.pipkin.ui.components.HeaderBar
import io.jeph.pipkin.ui.components.IconButton28
import io.jeph.pipkin.ui.files.ExplorerToggle
import io.jeph.pipkin.ui.sidebar.tildePath
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.CloseSplitButton
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.TabCountButton
import io.jeph.pipkin.ui.workspace.rememberWorkspaceTabs

/**
 * One terminal tab in a pane: a header with the terminal's name over its folder, the workspace's tab
 * button, the terminal's actions and the explorer toggle, which act through [controls]; libghostty's terminal; and
 * Paseo's key bar above the keyboard. An ended shell shows a bar to start another.
 */
@Composable
internal fun TerminalScreen(session: TerminalSession, controls: PaneControls, modifier: Modifier = Modifier) {
  val graph = LocalContext.current.appGraph
  val hello by graph.hostClient.lastHello.collectAsStateWithLifecycle()
  val tabs = rememberWorkspaceTabs(controls.pane, session.cwd)
  var actionsOpen by remember(session) { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    HeaderBar(
      title = terminalTitle(session),
      subtitle = hello?.home?.let { tildePath(session.cwd, it) } ?: session.cwd,
      leading = { MenuButton(controls.onToggleSidebar) },
      trailing = {
        TabCountButton(count = tabs.count, onClick = controls.onOpenTabs)
        IconButton28(
          icon = R.drawable.ic_lucide_ellipsis,
          contentDescription = stringResource(R.string.terminal_actions),
          onClick = { actionsOpen = true },
        )
        ExplorerToggle(controls.onToggleExplorer)
        CloseSplitButton(controls)
      },
    )
    // The terminal view and its key state are per session; a new session starts fresh.
    key(session) { TerminalPane(session, Modifier.weight(1f)) }
  }

  if (actionsOpen) {
    TerminalActionsSheet(
      session = session,
      onNewTerminal = { graph.terminals.open(controls.pane, session.cwd) },
      onRestart = session::start,
      onClose = { graph.terminals.close(session) },
      onDismiss = { actionsOpen = false },
    )
  }
}

/** libghostty's terminal, the shell's status when it isn't running, and the key bar above the keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalPane(session: TerminalSession, modifier: Modifier) {
  val colors = pipkinColors
  val context = LocalContext.current
  val state by session.state.collectAsStateWithLifecycle()
  val terminalState = rememberGhosttyTerminalState()
  var shift by remember { mutableStateOf(false) }
  val keyboardShown = WindowInsets.isImeVisible
  // The system "monospace" is proportional on some phones (OnePlus), which spreads text out in the cell grid.
  val typeface = remember(context) { checkNotNull(ResourcesCompat.getFont(context, R.font.jetbrains_mono)) }
  val theme = remember(colors) {
    GhosttyTheme(
      foreground = colors.foreground.toArgb(),
      background = colors.surface0.toArgb(),
      cursorColor = colors.foreground.toArgb(),
      selectionBackground = null,
      selectionForeground = null,
      palette = List(GHOSTTY_PALETTE_SIZE) { null },
    )
  }

  Column(modifier = modifier.fillMaxWidth()) {
    GhosttyTerminal(
      session = session.terminal,
      state = terminalState,
      // The view clears its background with drawColor, which fills past its bounds over the header unless clipped.
      modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
      theme = theme,
      darkColorScheme = colors.isDark,
      typeface = typeface,
      fontSizeSp = TERMINAL_FONT_SIZE_SP,
    )
    StatusBar(state, onRestart = session::start)
    TerminalKeyBar(
      extraKeys = terminalState.extraKeys,
      shift = shift,
      keyboardShown = keyboardShown,
      onToggleShift = { shift = !shift },
      onKey = { keyCode ->
        terminalState.view?.tapKey(keyCode, shift)
        shift = false
      },
      onPaste = { terminalState.view?.pasteFromClipboard() },
      onToggleKeyboard = {
        val view = terminalState.view
        if (keyboardShown) view?.hideIme() else view?.showIme()
      },
      modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    )
  }
  GhosttyDialogs(terminalState)
}

/** Compose's keyboard controller only hides a keyboard a Compose text field opened, not this view's. */
private fun GhosttyTerminalView.hideIme() {
  context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
}

/**
 * Presses and releases [keyCode] as a key event, so libghostty encodes it with [shift] and its own sticky Ctrl and
 * Alt, which it then lets go.
 */
private fun GhosttyTerminalView.tapKey(keyCode: Int, shift: Boolean) {
  val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
  val now = SystemClock.uptimeMillis()
  dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
  dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
}

private const val TERMINAL_FONT_SIZE_SP = 12f
private const val GHOSTTY_PALETTE_SIZE = 256

/** Says the shell is starting, or why it stopped with a way to start another. */
@Composable
private fun StatusBar(state: TerminalState, onRestart: () -> Unit) {
  val message = when (state) {
    TerminalState.Running -> return

    TerminalState.Starting -> stringResource(R.string.terminal_starting)

    is TerminalState.Exited -> state.code?.let { stringResource(R.string.terminal_exited_code, it) }
      ?: stringResource(R.string.terminal_exited)

    is TerminalState.Failed -> state.message
  }
  val colors = pipkinColors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = message,
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      modifier = Modifier.weight(1f),
    )
    if (state != TerminalState.Starting) {
      TextButton(onClick = onRestart) {
        Text(
          text = stringResource(R.string.terminal_restart),
          color = colors.accent,
          fontSize = PipkinTokens.FontSize.sm,
        )
      }
    }
  }
}
