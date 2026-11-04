package io.jeph.pipkin.ui.settings

import io.jeph.pipkin.host.HostRejected
import io.jeph.pipkin.host.HostText
import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHostFormTest {
  @Test
  fun aBlankPortIsSshsOwn() {
    assertEquals(22, parseSshPort(""))
    assertEquals(22, parseSshPort("  "))
  }

  @Test
  fun portsInRangeAreRead() {
    assertEquals(1, parseSshPort("1"))
    assertEquals(2222, parseSshPort(" 2222 "))
    assertEquals(65_535, parseSshPort("65535"))
  }

  @Test
  fun anythingButAPortNumberIsRefused() {
    assertNull(parseSshPort("0"))
    assertNull(parseSshPort("65536"))
    assertNull(parseSshPort("abc"))
    assertNull(parseSshPort("+22"))
    assertNull(parseSshPort("-22"))
    assertNull(parseSshPort("2 2"))
    // Too long for an Int: refused, not wrapped around.
    assertNull(parseSshPort("99999999999"))
    // Digits of other scripts aren't a port either.
    assertNull(parseSshPort("٢٢"))
  }

  @Test
  fun aValidationRefusalNamesItsField() {
    assertEquals(RemoteHostField.Name, RemoteHostField.of(invalid("name")))
    assertEquals(RemoteHostField.Address, RemoteHostField.of(invalid("address")))
    assertEquals(RemoteHostField.Port, RemoteHostField.of(invalid("port")))
  }

  @Test
  fun otherRefusalsAreAboutNoField() {
    assertNull(RemoteHostField.of(invalid("home")))
    assertNull(RemoteHostField.of(HostRejected("remote_host_invalid", "Bad host.")))
    val unreachable = HostText("Couldn't reach devbox", "remote_unreachable", mapOf("field" to "address"))
    assertNull(RemoteHostField.of(HostRejected("remote_unreachable", unreachable.english, unreachable)))
  }

  @Test
  fun aNewHostIsDirtyOnceSomethingIsTyped() {
    val form = RemoteHostForm.of(null)
    assertFalse(form.dirty(null))
    form.address = "me@devbox.lan"
    assertTrue(form.dirty(null))
  }

  @Test
  fun aSavedHostIsDirtyOnlyWhenAFieldChanges() {
    val saved = RemoteHost("h1", "devbox", RemoteAuth.Key, "me@devbox.lan", null, "/home/me")
    val form = RemoteHostForm.of(saved)
    // A saved port of null is SSH's own, which the field shows as 22.
    assertFalse(form.dirty(saved))
    form.port = "2222"
    assertTrue(form.dirty(saved))
    // A config host has no port, so its field doesn't count.
    form.auth = RemoteAuth.Config
    assertTrue(form.dirty(saved))
    val config = saved.copy(auth = RemoteAuth.Config)
    val configForm = RemoteHostForm.of(config)
    configForm.port = "x"
    assertFalse(configForm.dirty(config))
  }

  private fun invalid(field: String): HostRejected {
    val text = HostText("Enter a name.", "remote_name_empty", mapOf("field" to field))
    return HostRejected("remote_host_invalid", text.english, text)
  }
}
