package io.jeph.pipkin.ui.onboarding

import android.content.Context
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.appGraph
import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.termux.TermuxInstall
import io.jeph.pipkin.termux.TermuxSetup
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

/** Onboarding steps in display order; [OnboardingState.visibleSteps] drops the ones this phone or build skips. */
internal enum class SetupStep {
  Termux,
  Notifications,
  Background,
  TermuxBackground,
  RunCommand,
  TermuxSetup,
  InstallPi,
  SignIn,
}

/**
 * A failed attempt pinned to the step that fixes it. A null [message] means the generic install
 * failure text; [command], a literal command, replaces the setup command shown in that step.
 */
internal data class SetupError(val step: SetupStep, val message: String?, val command: String? = null)

internal fun HostFailure.toSetupError(): SetupError {
  val step = when (kind) {
    HostFailure.Kind.MissingTermux, HostFailure.Kind.UntrustedTermux -> SetupStep.Termux

    HostFailure.Kind.Permission -> SetupStep.RunCommand

    HostFailure.Kind.ExternalApps,
    HostFailure.Kind.NodeMissing,
    HostFailure.Kind.NodeTooOld,
    HostFailure.Kind.StorageDenied,
    HostFailure.Kind.TermuxNotInitialized,
    HostFailure.Kind.TermuxChanged,
    HostFailure.Kind.HelperNotPaired,
    HostFailure.Kind.HelperRejected,
    -> SetupStep.TermuxSetup

    else -> SetupStep.InstallPi
  }
  return SetupError(step, message, command)
}

/**
 * Onboarding progress that outlives the screen, kept in memory for the process: whether the setup
 * command was handed to Termux, which permissions Android stopped prompting for, and the Pi install.
 * The install runs in the app scope so leaving the screen does not abort `npm ci`.
 */
internal object SetupProgress {
  private const val PROGRESS_LINES = 4

  private val termuxCommandSentState = MutableStateFlow(false)
  private val playCommandState = MutableStateFlow<String?>(null)
  private val blockedPermissionsState = MutableStateFlow<Set<String>>(emptySet())
  private val installingState = MutableStateFlow(false)
  private val progressState = MutableStateFlow<List<String>>(emptyList())
  private val errorState = MutableStateFlow<SetupError?>(null)
  private var job: Job? = null

  /** The "Copy command & open Termux" button was pressed since the app started. */
  val termuxCommandSent: StateFlow<Boolean> = termuxCommandSentState.asStateFlow()

  /** The Play build's setup command last copied; it stays valid for a re-paste until it expires or is replaced. */
  val playCommand: StateFlow<String?> = playCommandState.asStateFlow()

  /** Permissions denied with "don't ask again"; their step opens system settings instead. */
  val blockedPermissions: StateFlow<Set<String>> = blockedPermissionsState.asStateFlow()

  val installing: StateFlow<Boolean> = installingState.asStateFlow()

  /** The last few `npm ci` output lines of the running or last install. */
  val progress: StateFlow<List<String>> = progressState.asStateFlow()

  /** Why the last install failed; cleared when the next one starts. */
  val error: StateFlow<SetupError?> = errorState.asStateFlow()

  /**
   * [literal] when a failure names its own fix, else the installed build's setup command; the Play build's opens a
   * fresh pairing session each time, which reads assets and may bind Pipkin's server, so it runs off the main thread.
   */
  suspend fun setupCommand(context: Context, literal: String?): String {
    termuxCommandSentState.value = true
    if (literal != null) return literal
    val command = withContext(Dispatchers.IO) { TermuxSetup.command(context, context.appGraph.keepAlive) }
    if (TermuxInstall.status(context).isPlay) playCommandState.value = command
    return command
  }

  fun setPermissionBlocked(permission: String, blocked: Boolean) {
    blockedPermissionsState.update { if (blocked) it + permission else it - permission }
  }

  /** Installs the pinned Pi runtime unless an install is already running. Main thread only. */
  fun install(graph: AppGraph) {
    if (job?.isActive == true) return
    errorState.value = null
    progressState.value = emptyList()
    installingState.value = true
    job = graph.scope.launch {
      try {
        graph.keepAlive.acquire("install Pi").use {
          graph.hostClient.ensure { line ->
            if (line.isNotBlank()) progressState.update { (it + line).takeLast(PROGRESS_LINES) }
          }
        }
        graph.accountRepository.refresh()
      } catch (failure: HostFailure) {
        errorState.value = failure.toSetupError()
      } catch (rejected: HostRejected) {
        errorState.value = SetupError(SetupStep.InstallPi, rejected.error)
      } catch (failure: IOException) {
        errorState.value = SetupError(SetupStep.InstallPi, failure.message)
      } catch (failure: JSONException) {
        errorState.value = SetupError(SetupStep.InstallPi, failure.message)
      } finally {
        installingState.value = false
      }
    }
  }
}
