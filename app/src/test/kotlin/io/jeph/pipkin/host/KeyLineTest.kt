package io.jeph.pipkin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyLineTest {
  // A real Pipkin key; `ssh-keygen -lf` prints SHA256:qJvEDTIK6yCn55k7eq443duiAdc8tVFxNuJpBFF8oKY for it.
  private val blob = "AAAAC3NzaC1lZDI1NTE5AAAAIK57I0beYtg+oDZ5Wj9WOsY5FYlQSerEW2RIWPhGcy+3"

  @Test
  fun fingerprintMatchesSshKeygen() {
    assertEquals(
      "SHA256:qJvEDTIK6yCn55k7eq443duiAdc8tVFxNuJpBFF8oKY",
      keyFingerprint("ssh-ed25519 $blob pipkin@CPH2765\n"),
    )
  }

  @Test
  fun fingerprintIsNullForALineWithoutAKey() {
    assertNull(keyFingerprint("ssh-ed25519"))
    assertNull(keyFingerprint("ssh-ed25519 not*base64 pipkin@x"))
  }

  @Test
  fun commentKeepsSpacesInTheDeviceModel() {
    assertEquals("pipkin@Pixel 9 Pro", keyComment("ssh-ed25519 $blob pipkin@Pixel 9 Pro"))
    assertEquals("", keyComment("ssh-ed25519 $blob"))
  }
}
