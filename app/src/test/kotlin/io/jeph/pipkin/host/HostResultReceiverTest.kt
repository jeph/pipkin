package io.jeph.pipkin.host

import org.junit.Assert.assertEquals
import org.junit.Test

class HostResultReceiverTest {
  private fun kind(exitCode: Int, err: Int = -1, errmsg: String = "") =
    HostResultReceiver.failureFor(exitCode, err, errmsg).kind

  @Test
  fun bootstrapExitCodesMapToSetupKinds() {
    assertEquals(HostFailure.Kind.TermuxNotInitialized, kind(70))
    assertEquals(HostFailure.Kind.NodeMissing, kind(72))
    assertEquals(HostFailure.Kind.NodeTooOld, kind(73))
    assertEquals(HostFailure.Kind.NodeMissing, kind(74))
    assertEquals(HostFailure.Kind.StorageDenied, kind(75))
    assertEquals(HostFailure.Kind.Internal, kind(80))
  }

  @Test
  fun onlyTheExternalAppsMessageMeansSetup() {
    assertEquals(HostFailure.Kind.ExternalApps, kind(70, err = 2, errmsg = "Set allow-external-apps=true"))
    assertEquals(HostFailure.Kind.TermuxError, kind(-1, err = 2, errmsg = "Permission denied"))
    assertEquals(HostFailure.Kind.TermuxError, kind(-1, err = 2))
  }

  @Test
  fun termuxStoppingItsServiceIsAnInterruptionNotSetup() {
    // Cancelled while queued, or SIGKILLed while starting, as Termux's service stopped.
    assertEquals(HostFailure.Kind.Interrupted, kind(-1, err = 0))
    assertEquals(HostFailure.Kind.Interrupted, kind(137, err = 2))
    assertEquals(false, HostResultReceiver.failureFor(-1, 0, "").needsSetup)
  }

  @Test
  fun otherExitsBeforeConnectingAreCrashes() {
    val failure = HostResultReceiver.failureFor(1, -1, "")
    assertEquals(HostFailure.Kind.Crashed, failure.kind)
    assertEquals(1, (failure as HostFailure.Crashed).exitCode)
  }
}
