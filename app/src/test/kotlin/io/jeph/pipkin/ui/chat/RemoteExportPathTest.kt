package io.jeph.pipkin.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteExportPathTest {
  private val home = "/data/data/com.termux/files/home"
  private val session = "$home/.pi/agent/sessions/--x--/2026-11-04_abc.jsonl"

  @Test
  fun aBlankPathGetsPisNameInTheHome() {
    assertEquals("$home/pi-session-2026-11-04_abc.html", remoteExportPath("", home, session))
  }

  @Test
  fun aRelativePathGoesUnderTheHome() {
    assertEquals("$home/out/chat.html", remoteExportPath("out/chat.html", home, session))
    // Pi doesn't expand a tilde that isn't followed by a slash, so this is a relative name too.
    assertEquals("$home/~report.html", remoteExportPath("~report.html", home, session))
  }

  @Test
  fun pathsPiResolvesWithoutTheCwdStayAsTyped() {
    assertEquals("/sdcard/chat.html", remoteExportPath("/sdcard/chat.html", home, session))
    assertEquals("~/chat.html", remoteExportPath("~/chat.html", home, session))
    assertEquals("~", remoteExportPath("~", home, session))
  }

  @Test
  fun anInMemorySessionIsLeftForPiToRefuse() {
    assertEquals("", remoteExportPath("", home, null))
  }
}
