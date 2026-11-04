package io.jeph.pipkin.ui.sidebar

import io.jeph.pipkin.host.RemoteAuth
import io.jeph.pipkin.host.RemoteHost
import io.jeph.pipkin.host.RemoteHostsState
import io.jeph.pipkin.host.RemoteWorkspace
import io.jeph.pipkin.host.remoteAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNamesTest {
  private val home = "/data/data/com.termux/files/home"
  private val standInRoot = "/data/data/com.termux/files/home/.pipkin/remotes"
  private val devbox = RemoteHost("h1", "devbox", RemoteAuth.Key, "me@devbox.lan", null, "/home/me")
  private val api = RemoteWorkspace("$standInRoot/h1/aaaa", "h1", "/home/me/src/api")
  private val homeFolder = RemoteWorkspace("$standInRoot/h1/bbbb", "h1", "/home/me")
  private val orphan = RemoteWorkspace("$standInRoot/gone/cccc", "gone", "/srv/www")
  private val names = FolderNames(
    home = home,
    remotes = RemoteHostsState(hosts = listOf(devbox), workspaces = listOf(api, homeFolder, orphan)),
    removedHost = "Removed host",
    separator = " · ",
  )

  @Test
  fun aStandInShowsAsTheRemoteFolderAfterItsHost() {
    assertEquals("api", names.name(api.cwd))
    assertEquals("devbox · ~/src/api", names.path(api.cwd))
  }

  @Test
  fun aStandInWithATrailingSlashIsStillRecognised() {
    assertEquals("devbox · ~/src/api", names.path(api.cwd + "/"))
  }

  @Test
  fun theRemoteHomeIsNamedTilde() {
    assertEquals("~", names.name(homeFolder.cwd))
    assertEquals("devbox · ~", names.path(homeFolder.cwd))
  }

  @Test
  fun aSubfolderOfAStandInIsShownOnTheRemote() {
    assertEquals("devbox · ~/src/api/lib", names.path(api.cwd, "lib"))
    assertEquals("/home/me/src/api/lib/a.kt", names.absolutePath(api.cwd, "lib/a.kt"))
  }

  @Test
  fun aWorkspaceOfARemovedHostSaysSoAndKeepsItsAbsolutePath() {
    assertEquals("www", names.name(orphan.cwd))
    assertEquals("Removed host · /srv/www", names.path(orphan.cwd))
  }

  @Test
  fun aPhoneFolderIsShownAsBefore() {
    assertFalse(names.isRemote("$home/src/app"))
    assertNull(names.remote("$home/src/app"))
    assertEquals("app", names.name("$home/src/app"))
    assertEquals("~/src/app", names.path("$home/src/app"))
  }

  @Test
  fun theHostAndThePathOnItAreAlsoGivenApart() {
    assertEquals("devbox", names.hostName(api.cwd))
    assertEquals("~/src/api", names.machinePath(api.cwd))
    assertEquals("Removed host", names.hostName(orphan.cwd))
    assertEquals("/srv/www", names.machinePath(orphan.cwd))
    assertNull(names.hostName("$home/src/app"))
    assertEquals("~/src/app", names.machinePath("$home/src/app"))
  }

  @Test
  fun theStandInOfARemoteFolderIsFoundWhateverItsTrailingSlash() {
    assertEquals(api.cwd, names.standIn("h1", "/home/me/src/api/"))
    assertNull(names.standIn("h1", "/home/me/src"))
  }

  @Test
  fun aHostWhoseHomeIsntKnownYetShowsAbsolutePaths() {
    val fresh = devbox.copy(home = null)
    assertEquals("devbox · /home/me/src", names.picker(fresh).path("/home/me/src"))
    assertEquals("me", remoteName("/home/me", null))
  }

  @Test
  fun thePickerShowsPhoneFoldersAgainstTheTermuxHome() {
    assertEquals("~/src", names.picker(null).path("$home/src"))
    assertEquals("devbox · ~/src", names.picker(devbox).path("/home/me/src"))
  }

  @Test
  fun theRemoteRootKeepsItsSlash() {
    assertEquals("/", remoteName("/", "/home/me"))
  }

  @Test
  fun anAddressShowsItsPortOnlyWhenItIsntSshs() {
    assertEquals("me@devbox.lan", remoteAddress("me@devbox.lan", 22))
    assertEquals("me@devbox.lan", remoteAddress("me@devbox.lan", null))
    assertEquals("me@[fe80::1]:2222", remoteAddress("me@[fe80::1]", 2222))
  }

  @Test
  fun aConfigHostHasNoPortInItsAddress() {
    val config = devbox.copy(auth = RemoteAuth.Config, address = "devbox", port = 2222)
    assertEquals("devbox", config.displayAddress)
  }
}
