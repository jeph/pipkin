package io.jeph.pipkin.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeph.pipkin.R
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.ui.auth.LoginSheet
import io.jeph.pipkin.ui.components.RoundIconButton
import io.jeph.pipkin.ui.components.SimmeringPot
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.delay

/**
 * First-run setup, one step per page: welcome, then every [SetupStep] that still needs the user, then a closing page.
 * Steps that are already done are passed over, and finishing one moves on by itself, so the wizard reacts to what the
 * user did elsewhere (installed Termux, ran the setup command, granted a permission). Skipped optional steps and the
 * welcome are remembered, so reopening Pipkin resumes at the first step still open. Opened again after setup (a
 * "needs attention" tap), it starts at the broken step and [onClose] leaves it.
 */
@Composable
internal fun OnboardingScreen(onFinished: () -> Unit, onAddProvider: () -> Unit, onClose: (() -> Unit)?) {
  val context = LocalContext.current
  val graph = remember(context) { context.appGraph }
  var loginProvider by rememberSaveable { mutableStateOf<String?>(null) }
  var poll by remember { mutableStateOf(false) }
  val checks = rememberDeviceChecks(poll)
  val state = rememberOnboardingState(graph, checks.value)
  val onAction = rememberSetupActions(
    state = state,
    onPermissionResult = checks::refresh,
    onLogin = { loginProvider = it },
    onAddProvider = onAddProvider,
  )
  AutoInstall(graph, state)
  val history = rememberSaveable(saver = HistorySaver) { mutableStateListOf(firstPage(state)) }
  val page = history.last()
  val step = stepOf(page)
  // Installing Termux or running its setup finishes in another app, which may not bring Pipkin back.
  SideEffect {
    poll = (step == SetupStep.Termux || step == SetupStep.TermuxSetup) && state.status(step) != StepStatus.Done
  }

  fun goTo(next: String) {
    if (next != history.last()) history.add(next)
  }

  fun advance(from: OnboardingState = state) = goTo(from.nextOpenStep()?.name ?: DONE)

  fun pass(passed: String) {
    graph.preferences.passSetupPage(passed)
    advance(state.copy(passedPages = state.passedPages + passed))
  }

  fun finish() {
    graph.preferences.setSetupComplete(true)
    onFinished()
  }

  fun back() {
    if (history.size > 1) history.removeAt(history.lastIndex) else onClose?.invoke()
  }

  AdvanceWhenDone(page, step, state) { advance(it) }
  // A failure pinned to another step, such as an install that finds Termux's setup broken, shows on that step.
  val failedStep = state.currentError?.step?.takeIf { it in state.visibleSteps }
  LaunchedEffect(failedStep) { failedStep?.let { goTo(it.name) } }
  BackHandler(enabled = history.size > 1 || onClose != null) { back() }

  Column(
    modifier = Modifier.fillMaxSize().background(pipkinColors.surface0).windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    WizardTopBar(
      state = state,
      step = step,
      onBack = if (history.size > 1) ::back else null,
      onClose = onClose,
    )
    AnimatedContent(
      targetState = page,
      modifier = Modifier.weight(1f),
      transitionSpec = {
        val forward = order(targetState) >= order(initialState)
        val shift = { width: Int -> if (forward) width / SLIDE_FRACTION else -width / SLIDE_FRACTION }
        (slideInHorizontally(tween(PAGE_MILLIS), shift) + fadeIn(tween(PAGE_MILLIS)))
          .togetherWith(slideOutHorizontally(tween(PAGE_MILLIS)) { -shift(it) } + fadeOut(tween(PAGE_MILLIS / 2)))
      },
      label = "onboarding page",
    ) { shown ->
      val shownStep = stepOf(shown)
      when {
        shown == WELCOME -> WelcomePage(onStart = { pass(WELCOME) })

        shownStep == null -> DonePage(signedIn = state.authStatus?.anyConfigured == true, onFinish = ::finish)

        else -> StepPage(
          spec = stepSpec(shownStep, state, onAction),
          onContinue = { advance() },
          onSkip = { pass(shownStep.name) },
        )
      }
    }
  }

  loginProvider?.let { LoginSheet(providerId = it, onDismiss = { loginProvider = null }) }
}

/**
 * Moves on once the shown [step] gets done while it's on screen, after a beat that lets its check settle; a step that
 * was already done when the user came back to it waits for Continue. A step this phone or build no longer shows
 * (Termux swapped between builds) moves on right away.
 */
@Composable
private fun AdvanceWhenDone(
  page: String,
  step: SetupStep?,
  state: OnboardingState,
  advance: (OnboardingState) -> Unit,
) {
  if (step == null) return
  val done = state.status(step) == StepStatus.Done
  val doneOnArrival = remember(page) { done }
  val currentState by rememberUpdatedState(state)
  val currentAdvance by rememberUpdatedState(advance)
  val hidden = step !in state.visibleSteps
  LaunchedEffect(page, done, hidden) {
    if (hidden) {
      currentAdvance(currentState)
    } else if (done && !doneOnArrival) {
      delay(DONE_PAUSE_MILLIS)
      currentAdvance(currentState)
    }
  }
}

@Composable
private fun WelcomePage(onStart: () -> Unit) {
  var arrived by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { arrived = true }
  // The pot settles in from slightly smaller once the page appears.
  val scale by animateFloatAsState(
    targetValue = if (arrived) 1f else WELCOME_START_SCALE,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    label = "welcome pot",
  )
  WizardPage(
    illustration = {
      SimmeringPot(
        modifier = Modifier.graphicsLayer {
          scaleX = scale
          scaleY = scale
        },
        size = POT_SIZE,
        tint = pipkinColors.foreground,
      )
    },
    title = stringResource(R.string.onboarding_welcome_title),
    body = stringResource(R.string.onboarding_welcome_body),
    buttons = { WizardPrimary(stringResource(R.string.onboarding_get_started), onStart) },
  )
}

@Composable
private fun DonePage(signedIn: Boolean, onFinish: () -> Unit) {
  WizardPage(
    illustration = { SimmeringPot(size = POT_SIZE, tint = pipkinColors.foreground) },
    title = stringResource(R.string.onboarding_done_title),
    body = stringResource(if (signedIn) R.string.onboarding_done_body else R.string.onboarding_done_body_no_model),
    buttons = { WizardPrimary(stringResource(R.string.onboarding_start), onFinish) },
  )
}

/** One setup step as a page: its illustration, text and live status, with its actions at the bottom. */
@Composable
private fun StepPage(spec: StepSpec, onContinue: () -> Unit, onSkip: () -> Unit) {
  WizardPage(
    illustration = { StepIllustration(spec) },
    tag = requirementLabel(spec.step.requirement),
    title = spec.title,
    body = spec.body,
    content = {
      spec.error?.let {
        Text(
          text = it,
          color = pipkinColors.destructive,
          fontSize = PipkinTokens.FontSize.base,
          textAlign = TextAlign.Center,
        )
      }
      spec.waiting?.let { WaitingRow(it) }
      spec.extra?.invoke()
      spec.note?.let {
        Text(
          text = it,
          color = pipkinColors.foregroundMuted,
          fontSize = PipkinTokens.FontSize.meta,
          lineHeight = NOTE_LINE_HEIGHT,
          textAlign = TextAlign.Center,
        )
      }
    },
    buttons = { StepButtons(spec, onContinue, onSkip) },
  )
}

/**
 * The setup command shows how to paste it until it has run; the agent install simmers while it runs; the rest show
 * their icon.
 */
@Composable
private fun StepIllustration(spec: StepSpec) {
  val done = spec.status == StepStatus.Done
  when {
    spec.step == SetupStep.TermuxSetup && !done -> PasteDemo()

    spec.step == SetupStep.InstallPi && spec.status == StepStatus.Running ->
      SimmeringPot(size = POT_SIZE, tint = pipkinColors.foreground)

    else -> IconTile(icon = spec.icon, done = done)
  }
}

private fun firstPage(state: OnboardingState): String = when {
  !state.setupComplete && OnboardingState.WELCOME !in state.passedPages -> WELCOME
  else -> state.nextOpenStep()?.name ?: DONE
}

private fun stepOf(page: String): SetupStep? = SetupStep.entries.firstOrNull { it.name == page }

/** Page order for the slide direction: welcome first, the steps in setup order, then the closing page. */
private fun order(page: String): Int = when (page) {
  WELCOME -> -1
  else -> stepOf(page)?.ordinal ?: SetupStep.entries.size
}

private val HistorySaver = listSaver<SnapshotStateList<String>, String>(
  save = { it.toList() },
  restore = { it.toMutableStateList() },
)

private const val WELCOME = OnboardingState.WELCOME
private const val DONE = "done"
private const val PAGE_MILLIS = 320
private const val SLIDE_FRACTION = 5
private const val DONE_PAUSE_MILLIS = 900L
private const val WELCOME_START_SCALE = 0.85f
private val POT_SIZE = 112.dp
private val NOTE_LINE_HEIGHT = 19.sp
