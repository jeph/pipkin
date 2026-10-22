package io.jeph.pipkin.ui.terminal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.terminal.TerminalSession
import io.jeph.pipkin.terminal.TerminalState
import io.jeph.pipkin.ui.components.PillButton
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import io.jeph.pipkin.ui.workspace.PaneControls
import io.jeph.pipkin.ui.workspace.PaneHeader
import io.jeph.pipkin.ui.workspace.PublishTabActions
import io.jeph.pipkin.ui.workspace.TabAction
import io.jeph.pipkin.ui.workspace.TabActionSet
import io.jeph.pipkin.workspace.WorkspaceTab

/**
 * One terminal tab in a pane: the pane's header with the terminal's name over its folder, which acts through
 * [controls]; libghostty's terminal; and a key bar above the keyboard. An ended shell shows a bar to start another.
 * What the terminal can do goes to the session sheet its title opens.
 */
@Composable
internal fun TerminalScreen(session: TerminalSession, controls: PaneControls, modifier: Modifier = Modifier) {
  val graph = LocalContext.current.appGraph
  var dialog by rememberSaveable(session.id) { mutableStateOf<TerminalDialog?>(null) }

  Column(modifier = modifier.fillMaxSize().background(pipkinColors.surface0)) {
    PaneHeader(title = terminalTitle(session), cwd = session.cwd, controls = controls)
    // The terminal view and its key state are per session; a new session starts fresh.
    key(session) { TerminalPane(session, Modifier.weight(1f)) }
  }
  LaunchedEffect(session) { session.startOnce() }

  PublishTabActions(
    controls.shownTab,
    TabActionSet(
      title = R.string.shell_this_terminal,
      actions = listOf(
        TabAction(R.string.terminal_new, R.drawable.ic_lucide_square_terminal, {
          graph.terminals.open(controls.pane, session.cwd)
        }),
        TabAction(R.string.terminal_rename, R.drawable.ic_lucide_pencil, { dialog = TerminalDialog.Rename }),
        TabAction(R.string.terminal_restart, R.drawable.ic_lucide_refresh_cw, session::start),
        TabAction(
          R.string.terminal_close,
          R.drawable.ic_lucide_x,
          { dialog = TerminalDialog.ConfirmClose },
          destructive = true,
        ),
      ),
    ),
  )
  dialog?.let {
    TerminalDialogs(session, it, onClose = { controls.onCloseTab(WorkspaceTab.Terminal(session)) }) { dialog = null }
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
      // The keyboard would cover half the screen before there is anything to type; a tap on the terminal or the key
      // bar's keyboard button raises it.
      focusOnAttach = false,
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
      .background(colors.surface1)
      .topHairline(colors.border)
      .padding(horizontal = PipkinTokens.Spacing.s16, vertical = PipkinTokens.Spacing.s6),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(PipkinTokens.Spacing.s12),
  ) {
    Text(
      text = message,
      color = colors.foregroundMuted,
      fontSize = PipkinTokens.FontSize.sm,
      modifier = Modifier.weight(1f),
    )
    if (state != TerminalState.Starting) {
      PillButton(label = stringResource(R.string.terminal_restart), onClick = onRestart)
    }
  }
}
