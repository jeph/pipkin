package io.jeph.pipkin.termux

import io.jeph.pipkin.termux.TermuxInstall.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxInstallTest {
  @Test
  fun onlyFDroidAndPlaySignaturesAreTrusted() {
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(UNKNOWN, "0.118.0"))
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(UNKNOWN, "googleplay.2026.10.09"))
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(null, "0.118.0"))
    // Termux's GitHub builds are signed with a test key whose password is public, so they prove nothing.
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(GITHUB, "0.118.3"))
  }

  @Test
  fun fDroidBuildsAreSupportedFrom0118BetasIncluded() {
    assertEquals(Status.FDroid, TermuxInstall.classify(FDROID, "0.118.3"))
    assertEquals(Status.FDroid, TermuxInstall.classify(FDROID, "0.119.0-beta.3"))
    assertEquals(Status.FDroid, TermuxInstall.classify(FDROID, "1.0"))
    assertEquals(Status.FDroidTooOld, TermuxInstall.classify(FDROID, "0.117.0"))
    assertEquals(Status.FDroidTooOld, TermuxInstall.classify(FDROID, "0.117.0-beta.1"))
  }

  @Test
  fun anFDroidBuildWithAnUnreadableVersionIsTrustedOnItsSignature() {
    assertEquals(Status.FDroid, TermuxInstall.classify(FDROID, "googleplay.2024"))
    assertEquals(Status.FDroid, TermuxInstall.classify(FDROID, null))
  }

  @Test
  fun playBuildsAreSupportedFromTheFirstTestedRelease() {
    assertEquals(Status.Play, TermuxInstall.classify(PLAY, "googleplay.2026.10.09"))
    assertEquals(Status.Play, TermuxInstall.classify(PLAY, "googleplay.2027.01.02"))
    assertEquals(Status.PlayTooOld, TermuxInstall.classify(PLAY, "googleplay.2026.10.08"))
    assertEquals(Status.PlayTooOld, TermuxInstall.classify(PLAY, "googleplay.2024.06.27"))
  }

  @Test
  fun aPlaySignedBuildWithoutADatedVersionOnlyAsksForAnUpdate() {
    // Termux's Play listing ran 0.1xx builds before the dated releases; any undated one is older than Pipkin supports.
    assertEquals(Status.PlayTooOld, TermuxInstall.classify(PLAY, "0.101"))
    assertEquals(Status.PlayTooOld, TermuxInstall.classify(PLAY, null))
  }

  private companion object {
    const val FDROID = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
    const val PLAY = "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9"
    const val GITHUB = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"
    const val UNKNOWN = "00"
  }
}
