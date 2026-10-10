package io.jeph.pipkin.termux

import io.jeph.pipkin.termux.TermuxInstall.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxInstallTest {
  @Test
  fun playStoreBuildIsDetectedByInstaller() {
    assertEquals(Status.PlayStore, TermuxInstall.classify(UNKNOWN, "0.120.0", "com.android.vending"))
  }

  @Test
  fun otherSignaturesAreUnsupported() {
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(UNKNOWN, "0.118.0", "org.fdroid.fdroid"))
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(null, "0.118.0", null))
  }

  @Test
  fun upstreamSignaturesAreCheckedForVersion() {
    assertEquals(Status.Supported, TermuxInstall.classify(FDROID, "0.118.3", "org.fdroid.fdroid"))
    assertEquals(Status.Supported, TermuxInstall.classify(GITHUB, "1.0.0", null))
    assertEquals(Status.TooOld, TermuxInstall.classify(FDROID, "0.117.0", "com.android.vending"))
    assertEquals(Status.UnsupportedBuild, TermuxInstall.classify(FDROID, "googleplay.2024", null))
  }

  private companion object {
    const val FDROID = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
    const val GITHUB = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"
    const val UNKNOWN = "00"
  }
}
