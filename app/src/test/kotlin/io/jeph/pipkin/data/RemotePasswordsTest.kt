package io.jeph.pipkin.data

import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteHost
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemotePasswordsTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val file get() = folder.root.resolve("remote-passwords.json")

  /** The Keystore's AES-GCM, with a key of the test's own; a new one is a Keystore that Android reset. */
  private class SoftwareCipher(private val key: SecretKey = newKey()) : PasswordCipher {
    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
      val iv = ByteArray(12).also(SecureRandom()::nextBytes)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
      cipher.updateAAD(associatedData)
      return iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
      cipher.updateAAD(associatedData)
      return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    companion object {
      fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
  }

  private val cipher = SoftwareCipher()
  private val host = RemoteHost("h1", "devbox", RemoteAuth.Password, "me@devbox.lan", port = null, home = null)
  private val prompt = "me@devbox.lan's password: "

  @Test
  fun aSavedPasswordSurvivesARestartWithThePromptItAnswers() {
    RemotePasswords(file, cipher).save(host, prompt, "hunter2 ünïcode")

    val restarted = RemotePasswords(file, cipher)
    assertEquals(RemotePasswords.Saved(prompt, "hunter2 ünïcode"), restarted.password(host))
    assertEquals(mapOf("h1" to false), restarted.saved.value)
    assertFalse(file.readText().contains("hunter2"))
  }

  @Test
  fun thePortSshUsesByDefaultIsTheSameServer() {
    val passwords = RemotePasswords(file, cipher)
    passwords.save(host, prompt, "secret")

    assertEquals("secret", passwords.password(host.copy(port = 22))?.password)
  }

  @Test
  fun aPasswordIsNeverHandedOutForAnotherServerOrHost() {
    val passwords = RemotePasswords(file, cipher)
    passwords.save(host, prompt, "secret")

    assertNull(passwords.password(host.copy(address = "me@other.lan")))
    assertNull(passwords.password(host.copy(port = 2222)))
    assertNull(passwords.password(host.copy(id = "h2")))
  }

  @Test
  fun anEntryMovedToAnotherHostDoesNotDecrypt() {
    val other = host.copy(id = "h2", name = "other")
    RemotePasswords(file, cipher).save(host, prompt, "secret")
    file.writeText(file.readText().replace("\"h1\"", "\"h2\""))

    val tampered = RemotePasswords(file, cipher)
    assertNull(tampered.password(other))
    // It's gone for good, so the next connect asks.
    assertEquals(emptyMap<String, Boolean>(), tampered.saved.value)
  }

  @Test
  fun aPasswordTheKeystoreCanNoLongerOpenCountsAsNotSaved() {
    RemotePasswords(file, cipher).save(host, prompt, "secret")

    val reset = RemotePasswords(file, SoftwareCipher())
    assertNull(reset.password(host))
    assertEquals(emptyMap<String, Boolean>(), RemotePasswords(file, cipher).saved.value)
  }

  @Test
  fun aRejectedPasswordIsNotSentAgainUntilANewOneIsSaved() {
    val passwords = RemotePasswords(file, cipher)
    passwords.save(host, prompt, "old")
    passwords.reject("h1")

    assertNull(passwords.password(host))
    assertEquals(mapOf("h1" to true), RemotePasswords(file, cipher).saved.value)

    passwords.save(host, prompt, "new")
    assertEquals("new", passwords.password(host)?.password)
    assertEquals(mapOf("h1" to false), passwords.saved.value)
  }

  @Test
  fun forgetDeletesOnlyThatHostsPassword() {
    val other = host.copy(id = "h2", name = "other")
    val passwords = RemotePasswords(file, cipher)
    passwords.save(host, prompt, "one")
    passwords.save(other, prompt, "two")

    passwords.forget("h1")
    assertNull(passwords.password(host))
    assertEquals("two", RemotePasswords(file, cipher).password(other)?.password)
  }

  @Test
  fun pruneDeletesPasswordsNoHostSignsInWithAnyMore() {
    val moved = host.copy(id = "moved")
    val switched = host.copy(id = "switched")
    val removed = host.copy(id = "removed")
    val kept = host.copy(id = "kept")
    val passwords = RemotePasswords(file, cipher)
    for (saved in listOf(moved, switched, removed, kept)) passwords.save(saved, prompt, "secret")

    passwords.prune(listOf(moved.copy(port = 2200), switched.copy(auth = RemoteAuth.Key), kept.copy(name = "renamed")))
    assertEquals(mapOf("kept" to false), RemotePasswords(file, cipher).saved.value)
  }

  @Test
  fun savingAChangedAddressPortOrSignInForgetsThePassword() {
    val passwords = RemotePasswords(file, cipher)
    assertFalse(passwords.forgetsOnSave(host, host.copy(address = "me@devbox2.lan")))
    passwords.save(host, prompt, "secret")

    assertTrue(passwords.forgetsOnSave(host, host.copy(address = "me@devbox2.lan")))
    assertTrue(passwords.forgetsOnSave(host, host.copy(port = 2222)))
    assertTrue(passwords.forgetsOnSave(host, host.copy(auth = RemoteAuth.Key)))
    assertFalse(passwords.forgetsOnSave(host, host.copy(name = "renamed", port = 22)))
  }

  @Test
  fun aDamagedFileStartsOver() {
    file.writeText("{ not json")

    val passwords = RemotePasswords(file, cipher)
    assertEquals(emptyMap<String, Boolean>(), passwords.saved.value)
    passwords.save(host, prompt, "secret")
    assertEquals("secret", RemotePasswords(file, cipher).password(host)?.password)
  }
}
