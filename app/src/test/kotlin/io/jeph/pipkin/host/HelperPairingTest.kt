package io.jeph.pipkin.host

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperPairingTest {
  private val dir = createTempDirectory().toFile()
  private val file = File(dir, "helper-pairing.json")
  private val store = HelperPairing.Store(file)

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  @Test
  fun startsUnpaired() {
    assertTrue(store.keys().all.isEmpty())
  }

  @Test
  fun aServedKeyIsPendingUntilAHelperUsesIt() {
    store.storePending(KEY)
    assertNull(store.keys().current)
    assertArrayEquals(KEY, store.keys().pending)

    assertTrue(store.confirm(KEY))
    assertArrayEquals(KEY, store.keys().current)
    assertNull(store.keys().pending)
  }

  @Test
  fun confirmingTheCurrentKeyKeepsANewerPendingOne() {
    store.storePending(KEY)
    store.confirm(KEY)
    store.storePending(OTHER_KEY)

    assertFalse(store.confirm(KEY))
    assertArrayEquals(KEY, store.keys().current)
    assertArrayEquals(OTHER_KEY, store.keys().pending)
    assertEquals(listOf(KEY.toHexString(), OTHER_KEY.toHexString()), store.keys().all.map { it.toHexString() })
  }

  @Test
  fun storesHexJsonWithoutLeavingATempFile() {
    store.storePending(KEY)
    val json = JSONObject(file.readText())
    assertTrue(json.isNull("current"))
    assertEquals(KEY.toHexString(), json.getString("pending"))
    assertEquals(listOf(file.name), dir.list()!!.toList())
  }

  @Test
  fun clearForgetsBothKeys() {
    store.storePending(KEY)
    store.confirm(KEY)
    store.storePending(OTHER_KEY)
    store.clear()
    assertTrue(store.keys().all.isEmpty())
  }

  @Test
  fun aDamagedFileReadsAsUnpaired() {
    file.writeText("""{"current":"${"zz".repeat(32)}"}""")
    assertTrue(store.keys().all.isEmpty())
    file.writeText("{")
    assertTrue(store.keys().all.isEmpty())
  }

  private companion object {
    val KEY = ByteArray(32) { it.toByte() }
    val OTHER_KEY = ByteArray(32) { 9 }
  }
}
