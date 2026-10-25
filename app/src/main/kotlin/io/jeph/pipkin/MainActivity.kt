package io.jeph.pipkin

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.jeph.pipkin.agent.ChatLocation
import io.jeph.pipkin.agent.ChatNotification
import io.jeph.pipkin.host.TermuxWaker
import io.jeph.pipkin.ui.AppNavigator
import io.jeph.pipkin.ui.ChatInboxOverlay
import io.jeph.pipkin.ui.Destination
import io.jeph.pipkin.ui.MainScreen
import io.jeph.pipkin.ui.ShellActions
import io.jeph.pipkin.ui.archive.ArchiveScreen
import io.jeph.pipkin.ui.archive.ArchivedChatScreen
import io.jeph.pipkin.ui.components.LoadingPill
import io.jeph.pipkin.ui.onboarding.OnboardingScreen
import io.jeph.pipkin.ui.onboarding.PermissionsScreen
import io.jeph.pipkin.ui.settings.AddModelsScreen
import io.jeph.pipkin.ui.settings.AddProviderScreen
import io.jeph.pipkin.ui.settings.AdvancedScreen
import io.jeph.pipkin.ui.settings.BackupsScreen
import io.jeph.pipkin.ui.settings.LicenseScreen
import io.jeph.pipkin.ui.settings.LicensesScreen
import io.jeph.pipkin.ui.settings.ModelEditorScreen
import io.jeph.pipkin.ui.settings.ProviderDrafts
import io.jeph.pipkin.ui.settings.ProviderEditorScreen
import io.jeph.pipkin.ui.settings.ProvidersScreen
import io.jeph.pipkin.ui.settings.SettingsScreen
import io.jeph.pipkin.ui.settings.SubscriptionScreen
import io.jeph.pipkin.ui.settings.ownsDraft
import io.jeph.pipkin.ui.subagents.ArchivedSubagentScreen
import io.jeph.pipkin.ui.theme.PipkinTheme
import io.jeph.pipkin.ui.theme.pipkinColors
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
  /** The chat a tapped notification asked for, until the main screen opens it. */
  private val requestedChat = MutableStateFlow<ChatLocation?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A recreated activity gets its launch intent again, but that chat was opened the first time.
    if (savedInstanceState == null) requestedChat.value = ChatNotification.location(intent)
    enableEdgeToEdge()
    // Android keeps an app behind the home screen on Back only when the home screen opened it, and the launcher icons
    // open this through LauncherActivity. Registered before the screens' own handlers, so it runs only when none of
    // them takes Back.
    onBackPressedDispatcher.addCallback(this) { if (isTaskRoot) moveTaskToBack(false) else finish() }
    val graph = appGraph
    setContent {
      val theme by graph.preferences.theme.collectAsStateWithLifecycle()
      PipkinTheme(theme) {
        // The app's theme can differ from the system's, so the bars' icons follow the app's.
        val dark = pipkinColors.isDark
        LaunchedEffect(dark) { enableEdgeToEdge(statusBarStyle(dark), navigationBarStyle(dark)) }
        Surface(modifier = Modifier.fillMaxSize(), color = pipkinColors.surface0) {
          PipkinApp(graph, requestedChat)
        }
      }
    }
  }

  private fun statusBarStyle(dark: Boolean) =
    if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)

  // androidx.activity's default scrims, picked by the app's theme rather than the system's. An auto style keeps gesture
  // navigation transparent on Android 10 to 14 and lets the system draw the scrim behind three-button navigation.
  private fun navigationBarStyle(dark: Boolean) = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    requestedChat.value = ChatNotification.location(intent)
  }

  override fun onStart() {
    super.onStart()
    TermuxWaker.pipkinShown()
  }

  override fun onStop() {
    super.onStop()
    TermuxWaker.pipkinCovered(this)
  }
}

private const val LIGHT_NAV_SCRIM = 0xE6FFFFFF.toInt()
private const val DARK_NAV_SCRIM = 0x801B1B1B.toInt()

/**
 * Shows the top of the navigator's stack; onboarding is the root until setup completes. Pushed
 * screens slide in from the end edge over the one below, and slide back out when popped.
 */
@Composable
private fun PipkinApp(graph: AppGraph, requestedChat: MutableStateFlow<ChatLocation?>) {
  val navigator = rememberSaveable(saver = AppNavigator.Saver) {
    AppNavigator(listOf(if (graph.preferences.setupComplete.value) Destination.Main else Destination.Onboarding))
  }
  val actions = remember(graph) { ShellActions(graph) }
  val stack by navigator.stack.collectAsStateWithLifecycle()
  val holder = rememberSaveableStateHolder()
  val drafts = remember { ProviderDrafts() }
  val chat by requestedChat.collectAsStateWithLifecycle()
  ForgetPopped(stack, holder)
  LaunchedEffect(stack) { if (stack.none(::ownsDraft)) drafts.release() }
  // Only while Pipkin is on screen: each sweep asks Termux for the chat list, which Android may stop in the background.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(actions, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { actions.autoArchive() } }
  // The main screen opens the requested chat once it is on top; before setup there is no main screen yet.
  LaunchedEffect(chat) {
    if (chat != null && Destination.Main in navigator.stack.value) navigator.popTo(Destination.Main)
  }
  BackHandler(enabled = stack.size > 1) { navigator.pop() }
  // The inbox's pill would cover the rows of a sidebar or explorer, so it waits until they close.
  var sidePanelOpen by remember { mutableStateOf(false) }
  Box(modifier = Modifier.fillMaxSize()) {
    AnimatedContent(
      targetState = stack,
      transitionSpec = { stackTransition(from = initialState.size, to = targetState.size) },
      contentKey = { AppNavigator.key(it.last()) },
      label = "navigation",
    ) { shown ->
      val top = shown.last()
      holder.SaveableStateProvider(AppNavigator.key(top)) {
        Screen(shown, navigator, actions, drafts, requestedChat, onSidePanelOpen = { sidePanelOpen = it })
      }
    }
    // The waking pill sits above the inbox's, so both show when both are up.
    ChatInboxOverlay(
      shown = stack.last() == Destination.Main && !sidePanelOpen,
      onOpen = { requestedChat.value = it },
    ) { WakingPill() }
  }
}

/** Pipkin opened Termux to start its helper and is waiting for it, on whichever screen asked, below its header bar. */
@Composable
private fun WakingPill() {
  val waking by TermuxWaker.waking.collectAsStateWithLifecycle()
  AnimatedVisibility(visible = waking, enter = fadeIn(), exit = fadeOut()) {
    LoadingPill(stringResource(R.string.termux_waking))
  }
}

/** The top of [shown]; model sub-pages edit the draft of the subscription or provider page below. */
@Composable
private fun Screen(
  shown: List<Destination>,
  navigator: AppNavigator,
  actions: ShellActions,
  drafts: ProviderDrafts,
  requestedChat: MutableStateFlow<ChatLocation?>,
  onSidePanelOpen: (Boolean) -> Unit,
) {
  when (val top = shown.last()) {
    Destination.Main -> MainScreen(
      actions = actions,
      requestedChat = requestedChat,
      onOpenSettings = { navigator.push(Destination.Settings) },
      onOpenProviders = { navigator.push(Destination.Providers) },
      onOpenArchive = { navigator.push(Destination.Archive) },
      onOpenArchivedChat = { navigator.push(Destination.ArchivedChat(it)) },
      onOpenOnboarding = { navigator.push(Destination.Onboarding) },
      onSidePanelOpen = onSidePanelOpen,
    )

    Destination.Archive, is Destination.ArchivedChat, is Destination.ArchivedSubagent ->
      ArchivePage(top, navigator, actions, requestedChat)

    Destination.Settings, Destination.Permissions, Destination.Backups, Destination.Advanced, Destination.Licenses,
    is Destination.License,
    -> SettingsPages(top, navigator)

    Destination.Providers -> ProvidersScreen(
      onBack = { navigator.pop() },
      onAdd = { navigator.push(Destination.AddProvider) },
      onOpenSubscription = { navigator.push(Destination.Subscription(it)) },
      onEditProvider = { navigator.push(Destination.ProviderEditor(it)) },
    )

    Destination.AddProvider -> AddProviderScreen(
      onBack = { navigator.pop() },
      onAddCompatible = { navigator.push(Destination.ProviderEditor(null)) },
      onAdded = { navigator.popTo(Destination.Providers) },
    )

    is Destination.Subscription -> SubscriptionScreen(
      form = remember { drafts.subscription(top.id) },
      onBack = { navigator.pop() },
      onAddModels = { navigator.push(Destination.AddModels) },
      onOpenModel = { navigator.push(Destination.ModelEditor(it)) },
    )

    is Destination.ProviderEditor -> ProviderEditorScreen(
      form = remember { drafts.provider(top.id) },
      onBack = { navigator.pop() },
      onDone = { navigator.popTo(Destination.Providers) },
      onAddModels = { navigator.push(Destination.AddModels) },
      onOpenModel = { navigator.push(Destination.ModelEditor(it)) },
    )

    Destination.AddModels -> AddModelsScreen(draft = remember { drafts.owner(shown) }, onBack = { navigator.pop() })

    is Destination.ModelEditor -> ModelEditorScreen(
      draft = remember { drafts.owner(shown) },
      modelId = top.modelId,
      onBack = { navigator.pop() },
    )

    Destination.Onboarding -> OnboardingScreen(
      onFinished = { navigator.replaceAll(Destination.Main) },
      onAddProvider = { navigator.push(Destination.ProviderEditor(null)) },
      // Before setup finishes the wizard is the root; opened from the main screen later, it can be closed.
      onClose = if (shown.size > 1) ({ navigator.pop() }) else null,
    )
  }
}

/** Settings, or one of the pages reached from it that needs nothing but the navigator. */
@Composable
private fun SettingsPages(top: Destination, navigator: AppNavigator) {
  when (top) {
    Destination.Permissions -> PermissionsScreen(onBack = { navigator.pop() })

    Destination.Backups -> BackupsScreen(onBack = { navigator.pop() })

    Destination.Advanced -> AdvancedScreen(onBack = { navigator.pop() })

    Destination.Licenses -> LicensesScreen(
      onBack = { navigator.pop() },
      onOpenLicense = { navigator.push(Destination.License(it)) },
    )

    is Destination.License -> LicenseScreen(path = top.path, onBack = { navigator.pop() })

    else -> SettingsScreen(
      onBack = { navigator.pop() },
      onOpenPermissions = { navigator.push(Destination.Permissions) },
      onOpenProviders = { navigator.push(Destination.Providers) },
      onOpenBackups = { navigator.push(Destination.Backups) },
      onOpenAdvanced = { navigator.push(Destination.Advanced) },
      onOpenLicenses = { navigator.push(Destination.Licenses) },
    )
  }
}

/**
 * The archive list, one archived chat's transcript, or the transcript of a subagent it ran; restoring a chat reopens it
 * on the main screen.
 */
@Composable
private fun ArchivePage(
  top: Destination,
  navigator: AppNavigator,
  actions: ShellActions,
  requestedChat: MutableStateFlow<ChatLocation?>,
) {
  when (top) {
    is Destination.ArchivedChat -> ArchivedChatScreen(
      path = top.path,
      actions = actions,
      onBack = { navigator.pop() },
      onRestored = { requestedChat.value = it },
      onOpenSubagent = { navigator.push(Destination.ArchivedSubagent(top.path, it)) },
    )

    is Destination.ArchivedSubagent -> ArchivedSubagentScreen(
      path = top.path,
      toolCallId = top.toolCallId,
      onBack = { navigator.pop() },
    )

    else -> ArchiveScreen(
      actions = actions,
      onBack = { navigator.pop() },
      onOpenChat = { navigator.push(Destination.ArchivedChat(it)) },
    )
  }
}

/**
 * Same timing as the sidebar panels. The screen underneath shifts a quarter width for depth; the
 * popped screen stays on top while it leaves. Replacing the whole stack cross-fades.
 *
 * AnimatedContent keeps the z-index a screen entered with, so every transition sets it to the
 * screen's stack depth: a deeper screen always draws over a shallower one, however it got there.
 */
private fun stackTransition(from: Int, to: Int): ContentTransform {
  val slide = tween<IntOffset>(durationMillis = SCREEN_SLIDE_MILLIS, easing = FastOutSlowInEasing)
  val fade = tween<Float>(durationMillis = SCREEN_SLIDE_MILLIS, easing = FastOutSlowInEasing)
  val transform = when {
    to > from -> slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / 4 }
    to < from -> slideInHorizontally(slide) { -it / 4 } togetherWith slideOutHorizontally(slide) { it }
    else -> fadeIn(fade) togetherWith fadeOut(fade)
  }
  return transform.apply { targetContentZIndex = to.toFloat() }
}

private const val SCREEN_SLIDE_MILLIS = 220

/** Drops the saved UI state of screens that left the stack, so reopening one starts fresh. */
@Composable
private fun ForgetPopped(stack: List<Destination>, holder: SaveableStateHolder) {
  val keys = stack.map(AppNavigator::key)
  val previous = remember { mutableListOf<String>() }
  LaunchedEffect(keys) {
    (previous - keys.toSet()).forEach(holder::removeState)
    previous.clear()
    previous.addAll(keys)
  }
}
