package io.jeph.pipkin.host

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySetupTest {
  private val servedKeys = mutableListOf<ByteArray>()

  @Test
  fun pairingMatchesTheSpecVector() {
    assertEquals(PAIRING_KEY, SetupSession.pairingKey(TOKEN).toHexString())
    val body = SetupSession.pairingBody(TOKEN, SID, KEY, ByteArray(12) { 0x22 })
    assertEquals(PAIRING_BODY, String(body, Charsets.US_ASCII))
  }

  @Test
  fun installerIsRenderedWithTheFilesHashesAndPackage() {
    val session = open()
    val installer = String(PlaySetup.respond("/pipkin/${session.sid}/setup.sh", NOW).body)
    assertEquals("h=${sha256("helper")} r=${sha256("run")} s=${sha256("start")} p=io.jeph.pipkin.debug\n", installer)
    assertEquals(sha256(installer), session.installerSha256)
  }

  @Test
  fun servesEachFileOfAnOpenSession() {
    val session = open()
    for ((name, body) in listOf("helper.mjs" to "helper", "run.sh" to "run", "start.sh" to "start")) {
      val response = PlaySetup.respond("/pipkin/${session.sid}/$name", NOW)
      assertEquals(200, response.status)
      assertEquals(body, String(response.body))
    }
  }

  @Test
  fun keyDecryptsWithTheTokenAndBecomesPending() {
    val session = open()
    val response = PlaySetup.respond("/pipkin/${session.sid}/key", NOW)

    assertEquals(200, response.status)
    val sealed = String(response.body, Charsets.US_ASCII).hexToByteArray()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      SecretKeySpec(SetupSession.pairingKey(session.token), "AES"),
      GCMParameterSpec(128, sealed.copyOfRange(0, 12)),
    )
    cipher.updateAAD("pipkin-pair|${session.sid}".toByteArray())
    assertArrayEquals(session.key, cipher.doFinal(sealed.copyOfRange(12, sealed.size)))
    assertEquals(1, servedKeys.size)
    assertArrayEquals(session.key, servedKeys.single())
  }

  @Test
  fun otherPathsAreNotFound() {
    val session = open()
    assertEquals(404, PlaySetup.respond("/", NOW).status)
    assertEquals(404, PlaySetup.respond("/pipkin/${session.sid}/host.mjs", NOW).status)
    assertEquals(404, PlaySetup.respond("/pipkin/${session.sid}/key?again", NOW).status)
    assertEquals(404, PlaySetup.respond("/pipkin/${session.sid}/../key", NOW).status)
  }

  @Test
  fun unknownSessionsAreGone() {
    open()
    assertEquals(410, PlaySetup.respond("/pipkin/$SID/setup.sh", NOW).status)
  }

  @Test
  fun aNewerCommandSupersedesTheSession() {
    val first = open()
    val second = open()
    assertEquals(410, PlaySetup.respond("/pipkin/${first.sid}/key", NOW).status)
    assertEquals(200, PlaySetup.respond("/pipkin/${second.sid}/key", NOW).status)
  }

  @Test
  fun sessionsExpire() {
    val session = open()
    assertEquals(200, PlaySetup.respond("/pipkin/${session.sid}/run.sh", EXPIRES - 1).status)
    assertEquals(410, PlaySetup.respond("/pipkin/${session.sid}/run.sh", EXPIRES).status)
  }

  @Test
  fun aHelperUsingTheKeyEndsOnlyTheKey() {
    val session = open()
    PlaySetup.complete(KEY)
    assertEquals(200, PlaySetup.respond("/pipkin/${session.sid}/key", NOW).status)
    PlaySetup.complete(session.key)
    assertEquals(410, PlaySetup.respond("/pipkin/${session.sid}/key", NOW).status)
    assertEquals(200, PlaySetup.respond("/pipkin/${session.sid}/setup.sh", NOW).status)
  }

  @Test
  fun commandRunsTheInstallerOnlyAfterItsHashChecksOnOneLine() {
    val session = open()
    val command = session.command(4242)
    val check = command.indexOf("echo \"${session.installerSha256}  \$f\" | sha256sum -c --status && ")
    val run = command.indexOf("bash \"\$f\"")
    assertTrue(check in 0 until run)
    assertFalse(command.contains('\n'))
  }

  private fun open(): SetupSession {
    val files = mapOf(
      HelperAssets.HELPER to "helper",
      HelperAssets.RUN to "run",
      HelperAssets.START to "start",
      HelperAssets.INSTALLER to "h=@HELPER_SHA256@ r=@RUN_SHA256@ s=@START_SHA256@ p=@PIPKIN_PACKAGE@\n",
    )
    val session = SetupSession.create(
      read = { files.getValue(it).toByteArray() },
      packageName = "io.jeph.pipkin.debug",
      expiresAt = EXPIRES,
      onKeyServed = { servedKeys += it },
    )
    PlaySetup.open(session)
    return session
  }

  private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()

  private companion object {
    const val NOW = 1_000_000L
    const val EXPIRES = NOW + 30 * 60_000L
    const val SID = "00112233445566778899aabbccddeeff"
    val TOKEN = ByteArray(32) { 0x11 }
    val KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()
    const val PAIRING_KEY = "5f0176d40fe08c42eb95805e16f536883a652dc2778578b27bb197ccf0d9e34c"
    const val PAIRING_BODY = "222222222222222222222222319d2a307af21c87b8dbc08698713d1eca98a069" +
      "61f19086e5b493fa03650cce9202f9300b110c5b7284c10e82768b8e"
  }
}
