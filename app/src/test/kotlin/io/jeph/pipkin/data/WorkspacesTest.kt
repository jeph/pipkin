package io.jeph.pipkin.data

import io.jeph.pipkin.host.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacesTest {
  private fun session(path: String, cwd: String) = SessionInfo(
    path = path,
    id = path,
    cwd = cwd,
    cwdExists = true,
    name = null,
    created = 0,
    modified = 0,
    messageCount = 0,
    firstMessage = "",
  )

  private fun summary(list: List<Workspace>) = list.map { it.cwd to it.chats.map(SessionInfo::path) }

  @Test
  fun emptyAddedFoldersComeFirstNewestAddedFirst() {
    val sessions = listOf(session("a2", "/w/a"), session("b1", "/w/b"), session("a1", "/w/a"))
    val result = workspaces(sessions, added = listOf("/w/new", "/w/older", "/w/b"))
    assertEquals(
      listOf(
        "/w/new" to emptyList(),
        "/w/older" to emptyList(),
        "/w/a" to listOf("a2", "a1"),
        "/w/b" to listOf("b1"),
      ),
      summary(result),
    )
  }

  @Test
  fun trailingSlashesAreTheSameFolder() {
    val result = workspaces(listOf(session("x", "/w/a/")), added = listOf("/w/a", "/w/c/", "/w/c"))
    assertEquals(listOf("/w/c" to emptyList(), "/w/a" to listOf("x")), summary(result))
    assertTrue(sameFolder("/w/a/", "/w/a"))
    assertEquals("/", folderKey("/"))
  }
}
