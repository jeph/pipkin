package io.jeph.pipkin.ui.onboarding

import io.jeph.pipkin.host.HostFailure
import io.jeph.pipkin.host.HostHello
import io.jeph.pipkin.termux.TermuxInstall.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
  @Test
  fun eachBuildGetsOnlyItsOwnTermuxStep() {
    assertTrue(SetupStep.RunCommand in state(fdroid()).visibleSteps)
    assertFalse(SetupStep.TermuxBackground in state(fdroid()).visibleSteps)
    assertTrue(SetupStep.TermuxBackground in state(play()).visibleSteps)
    assertFalse(SetupStep.RunCommand in state(play()).visibleSteps)
    // Below Android 13 there is no notification permission to ask for.
    assertFalse(SetupStep.Notifications in state(fdroid().copy(notificationsGranted = null)).visibleSteps)
  }

  @Test
  fun setupResumesAtTheFirstStepStillOpen() {
    assertEquals(SetupStep.Termux, state(fdroid().copy(termux = Status.NotInstalled)).nextOpenStep())
    assertEquals(SetupStep.RunCommand, state(fdroid()).nextOpenStep())
    assertEquals(SetupStep.TermuxSetup, state(fdroid(runCommand = true)).nextOpenStep())
    assertEquals(SetupStep.Notifications, state(fdroid(runCommand = true, reached = true)).nextOpenStep())
  }

  @Test
  fun skippedStepsStaySkippedButRequiredOnesComeBackWhenTheyBreak() {
    val passed = setOf(SetupStep.Notifications.name, SetupStep.Background.name)
    val done = state(fdroid(runCommand = true, reached = true), passed = passed, hello = READY)
    assertEquals(SetupStep.SignIn, done.nextOpenStep())
    // Revoking the RUN_COMMAND grant reopens its step even though later steps were finished.
    assertEquals(SetupStep.RunCommand, state(fdroid(reached = true), passed = passed, hello = READY).nextOpenStep())
    assertNull(
      state(
        fdroid(runCommand = true, reached = true),
        passed = passed + SetupStep.SignIn.name,
        hello = READY,
      ).nextOpenStep(),
    )
  }

  @Test
  fun aHostThatRanInThisTermuxProvesTheSetupWithoutLaunchingAgain() {
    assertEquals(StepStatus.Done, state(fdroid(runCommand = true, reached = true)).status(SetupStep.TermuxSetup))
    // A reinstall wiped the setup the earlier host relied on.
    val reinstalled = fdroid(runCommand = true, reached = true).copy(termuxChanged = true)
    assertEquals(StepStatus.Pending, state(reinstalled, hello = READY).status(SetupStep.TermuxSetup))
    // Without its key, Pipkin can't reach the Play helper the setup started.
    assertEquals(StepStatus.Pending, state(play(reached = true, paired = false)).status(SetupStep.TermuxSetup))
    assertEquals(StepStatus.Done, state(play(reached = true, paired = true)).status(SetupStep.TermuxSetup))
  }

  @Test
  fun setupWaitsForTermuxOnlyAfterTheCommandWasCopied() {
    assertFalse(state(fdroid(runCommand = true)).waitingForTermux)
    assertTrue(state(fdroid(runCommand = true), copied = true).waitingForTermux)
    val failed = SetupError(SetupStep.TermuxSetup, "Termux blocked Pipkin's commands.")
    assertFalse(state(fdroid(runCommand = true), copied = true, error = failed).waitingForTermux)
  }

  @Test
  fun theAgentInstallsByItselfOnceTermuxMayBeReady() {
    assertFalse(state(fdroid(runCommand = true)).shouldAutoInstall)
    assertTrue(state(fdroid(runCommand = true), copied = true).shouldAutoInstall)
    assertFalse(state(fdroid(), copied = true).shouldAutoInstall)
    assertFalse(state(play(paired = false), copied = true).shouldAutoInstall)
    assertTrue(state(play(paired = true), copied = true).shouldAutoInstall)
    assertFalse(state(fdroid(runCommand = true), copied = true, hello = READY).shouldAutoInstall)
  }

  @Test
  fun anEarlierHostDoesNotCountOnceTermuxIsRemovedOrReplaced() {
    assertTrue(state(fdroid(runCommand = true), hello = READY).piReady)
    val removed = checks(Status.NotInstalled)
    assertEquals(StepStatus.Pending, state(removed, hello = READY).status(SetupStep.TermuxSetup))
    assertEquals(StepStatus.Pending, state(removed, hello = READY).status(SetupStep.InstallPi))
    val replaced = fdroid(runCommand = true).copy(termuxChanged = true)
    assertFalse(state(replaced, hello = READY).piReady)
    // Setup finished for the old Termux; the new one waits for the command to be copied for it.
    assertFalse(state(replaced, complete = true, hello = READY).shouldAutoInstall)
    assertTrue(state(replaced, complete = true, copied = true, hello = READY).shouldAutoInstall)
    assertTrue(state(fdroid(runCommand = true), complete = true).shouldAutoInstall)
  }

  @Test
  fun aFailuresOwnFixCommandIsDroppedAfterAReinstall() {
    val tooOld = HostFailure.NodeTooOld().toSetupError()
    assertEquals("yes | pkg upgrade -y", state(fdroid(runCommand = true), error = tooOld).setupLiteral)
    assertNull(state(fdroid(runCommand = true).copy(termuxChanged = true), error = tooOld).setupLiteral)
  }

  @Test
  fun storageIsDoneOnlyWhenTheInstalledTermuxReportsIt() {
    val noStorage = READY.copy(sharedStorage = false)
    assertEquals(StepStatus.Done, state(fdroid(runCommand = true), hello = READY).status(SetupStep.Storage))
    assertEquals(StepStatus.Pending, state(fdroid(runCommand = true), hello = noStorage).status(SetupStep.Storage))
    val replaced = fdroid(runCommand = true).copy(termuxChanged = true)
    assertEquals(StepStatus.Pending, state(replaced, hello = READY).status(SetupStep.Storage))
    // Only a set-up Termux can be asked; one that reported no access is asked again.
    assertFalse(state(fdroid(runCommand = true)).storageUnconfirmed)
    assertTrue(state(fdroid(runCommand = true, reached = true)).storageUnconfirmed)
    assertTrue(state(fdroid(runCommand = true), hello = noStorage).storageUnconfirmed)
    assertFalse(state(fdroid(runCommand = true), hello = READY).storageUnconfirmed)
  }

  @Test
  fun aFailureFixedInSettingsStopsHoldingBackTheInstall() {
    val missing = SetupError(SetupStep.Termux, "Termux is not installed.")
    assertEquals(missing, state(checks(Status.NotInstalled), error = missing).currentError)
    assertNull(state(fdroid(runCommand = true), error = missing).currentError)
    // The step asks for RUN_COMMAND itself; Termux's refusal adds nothing, granted or not.
    val refused = SetupError(SetupStep.RunCommand, "Allow Pipkin to run commands in Termux.")
    assertNull(state(fdroid(), error = refused).currentError)
    assertTrue(state(fdroid(runCommand = true), complete = true, error = refused).shouldAutoInstall)
    val tooOld = HostFailure.NodeTooOld().toSetupError()
    assertEquals(tooOld, state(fdroid(runCommand = true), error = tooOld).currentError)
  }

  @Test
  fun aHelperThatWontStartReopensTheSetupStepOfAFinishedSetup() {
    val unreachable = HostFailure.HelperUnreachable().toSetupError()
    val state = state(play(reached = true, paired = true), complete = true, hello = READY, error = unreachable)
    assertEquals(StepStatus.Failed, state.status(SetupStep.TermuxSetup))
    assertEquals(SetupStep.TermuxSetup, state.nextOpenStep())
  }

  private fun fdroid(runCommand: Boolean = false, reached: Boolean = false) = checks(Status.FDroid).copy(
    runCommandGranted = runCommand,
    termuxReached = reached,
  )

  private fun play(reached: Boolean = false, paired: Boolean = false) = checks(Status.Play).copy(
    helperPaired = paired,
    termuxReached = reached,
  )

  private fun checks(termux: Status) = DeviceChecks(
    termux = termux,
    termuxVersion = "0.118.3",
    termuxInstallation = null,
    runCommandGranted = false,
    notificationsGranted = false,
    backgroundAllowed = false,
    termuxBackgroundAllowed = false,
    helperPaired = false,
    termuxChanged = false,
    termuxReached = false,
  )

  private fun state(
    checks: DeviceChecks,
    passed: Set<String> = emptySet(),
    complete: Boolean = false,
    copied: Boolean = false,
    hello: HostHello? = null,
    error: SetupError? = null,
  ) = OnboardingState(
    checks = checks,
    hello = hello,
    expectedPiVersion = PI_VERSION,
    setupComplete = complete,
    commandCopied = copied,
    passedPages = passed,
    blockedPermissions = emptySet(),
    installing = false,
    progress = emptyList(),
    error = error,
    authStatus = null,
  )

  private companion object {
    const val PI_VERSION = "1.3.0"
    val READY = HostHello(piVersion = PI_VERSION, nodeVersion = "22.19.0", configDir = "", home = "", sharedStorage = true)
  }
}
