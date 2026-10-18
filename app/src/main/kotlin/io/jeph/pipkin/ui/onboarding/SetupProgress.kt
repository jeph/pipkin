package io.jeph.pipkin.ui.onboarding

import android.content.Context
import io.jeph.pipkin.AppGraph
import io.jeph.pipkin.AppStrings
import io.jeph.pipkin.R
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

/** Whether onboarding can move past a step without it: required ones can't be skipped. */
internal enum class Requirement { Required, Recommended, Optional }

/**
 * Setup steps in the order onboarding asks for them: the required Termux work first, so the agent install runs while
 * the user handles the recommended steps. [OnboardingState.visibleSteps] drops the ones this phone or build skips.
 */
internal enum class SetupStep(val requirement: Requirement) {
  Termux(Requirement.Required),
  RunCommand(Requirement.Required),
  TermuxSetup(Requirement.Required),
  Notifications(Requirement.Recommended),
  Background(Requirement.Recommended),
  TermuxBackground(Requirement.Recommended),
  Storage(Requirement.Recommended),
  InstallPi(Requirement.Required),
  SignIn(Requirement.Optional),
}

/**
 * A failed attempt pinned to the step that fixes it: [failure] when Termux or Pipkin's helper caused it, else
 * [detail], the install's own reason. Neither means the generic install failure text. [command], a literal command,
 * replaces the setup command shown in that step.
 */
internal data class SetupError(
  val step: SetupStep,
  val detail: String? = null,
  val failure: HostFailure? = null,
  val command: String? = null,
) {
  /** What went wrong, in the app's language; null for the generic install failure. */
  val message: String?
    get() {
      // Opening Termux didn't start the helper, so setup asks for its command rather than for opening Termux again.
      val helperDidntStart = failure?.kind == HostFailure.Kind.HelperUnreachable
      if (helperDidntStart) return AppStrings.get(R.string.onboarding_helper_didnt_start)
      return failure?.message ?: detail
    }
}

internal fun HostFailure.toSetupError(): SetupError {
  val step = when (kind) {
    HostFailure.Kind.MissingTermux, HostFailure.Kind.UntrustedTermux -> SetupStep.Termux

    HostFailure.Kind.Permission -> SetupStep.RunCommand

    // Opening Termux didn't start the helper; whatever Termux reports, the setup command fixes.
    HostFailure.Kind.HelperUnreachable,
    HostFailure.Kind.ExternalApps,
    HostFailure.Kind.NodeMissing,
    HostFailure.Kind.NodeTooOld,
    HostFailure.Kind.TermuxNotInitialized,
    HostFailure.Kind.TermuxChanged,
    HostFailure.Kind.HelperNotPaired,
    HostFailure.Kind.HelperRejected,
    -> SetupStep.TermuxSetup

    else -> SetupStep.InstallPi
  }
  return SetupError(step, failure = this, command = command)
}

/**
 * Onboarding progress that outlives the screen, kept in memory for the process: which permissions Android stopped
 * prompting for, and the Pi install. The install runs in the app scope so leaving the screen does not abort `npm ci`.
 */
internal object SetupProgress {
  private const val PROGRESS_LINES = 4

  private val blockedPermissionsState = MutableStateFlow<Set<String>>(emptySet())
  private val installingState = MutableStateFlow(false)
  private val progressState = MutableStateFlow<List<String>>(emptyList())
  private val errorState = MutableStateFlow<SetupError?>(null)
  private val storagePromptShownState = MutableStateFlow(false)
  private var job: Job? = null
  private var storageCheck: Job? = null

  /** Permissions denied with "don't ask again"; their step opens system settings instead. */
  val blockedPermissions: StateFlow<Set<String>> = blockedPermissionsState.asStateFlow()

  val installing: StateFlow<Boolean> = installingState.asStateFlow()

  /** The last few `npm ci` output lines of the running or last install. */
  val progress: StateFlow<List<String>> = progressState.asStateFlow()

  /** Why the last install failed; cleared when the next one starts. */
  val error: StateFlow<SetupError?> = errorState.asStateFlow()

  /**
   * Termux's storage prompt was opened. Termux doesn't say how it went, and Android stops showing it after two
   * refusals, so a storage step still open after it offers All files access instead.
   */
  val storagePromptShown: StateFlow<Boolean> = storagePromptShownState.asStateFlow()

  fun markStoragePromptShown() {
    storagePromptShownState.value = true
  }

  /**
   * [literal] when a failure names its own fix, else the installed build's setup command; the Play build's opens a
   * fresh pairing session each time, which reads assets and may bind Pipkin's server, so it runs off the main thread.
   */
  suspend fun setupCommand(context: Context, literal: String?): String {
    TermuxInstall.installation(context)?.let(context.appGraph.preferences::setSetupCommandCopiedFor)
    return literal ?: withContext(Dispatchers.IO) { TermuxSetup.command(context, context.appGraph.keepAlive) }
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

  /**
   * Starts a host to read Termux's storage access afresh from its hello, after the user may have changed it in
   * settings. Failures are dropped: the step just stays pending, and the install or chat reports a broken Termux.
   */
  fun recheckStorage(graph: AppGraph) {
    if (storageCheck?.isActive == true) return
    storageCheck = graph.scope.launch {
      try {
        graph.hostClient.hello()
      } catch (_: IOException) {
      } catch (_: HostRejected) {
      } catch (_: JSONException) {
      }
    }
  }
}
