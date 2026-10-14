package io.jeph.pipkin.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHostTest {
  private fun loaded(reply: GitReply<GitChanges>): GitChanges {
    assertTrue("expected a loaded reply, got $reply", reply is GitReply.Loaded)
    return (reply as GitReply.Loaded).value
  }

  @Test
  fun `a changes reply parses its files and hunks, keeping each line's prefix`() {
    val reply = JSONObject(
      """
      {"id":1,"fingerprint":"abc","branch":"feature","baseRef":"origin/main","files":[
        {"path":"src/new.kt","oldPath":"src/old.kt","change":"renamed","additions":1,"deletions":1,"kind":"text",
         "hunks":[{"header":"@@ -1 +1 @@","oldStart":1,"newStart":1,"lines":["-a","+b","\\ No newline at end of file"]}]},
        {"path":"logo.png","change":"added","additions":0,"deletions":0,"kind":"binary"}
      ]}
      """,
    )

    val changes = loaded(parseGitChanges(reply))

    assertEquals("abc", changes.fingerprint)
    assertEquals("feature", changes.branch)
    assertEquals("origin/main", changes.baseRef)
    val (renamed, binary) = changes.diff.files
    assertEquals("src/new.kt", renamed.path)
    assertEquals(GitChange.Renamed, renamed.change)
    assertEquals(listOf("-a", "+b", "\\ No newline at end of file"), renamed.hunks.single().lines)
    assertEquals(GitFileKind.Binary, binary.kind)
    assertTrue(binary.hunks.isEmpty())
  }

  @Test
  fun `a detached HEAD and a missing base are JSON nulls, not the string null`() {
    val changes = loaded(parseGitChanges(JSONObject("""{"fingerprint":"f","branch":null,"baseRef":null,"files":[]}""")))

    assertNull(changes.branch)
    assertNull(changes.baseRef)
  }

  @Test
  fun `CRLF lines lose their carriage return`() {
    val reply = JSONObject(
      """
      {"fingerprint":"f","branch":"main","baseRef":null,"files":[{"path":"a.txt","change":"modified",
       "additions":1,"deletions":1,"kind":"text",
       "hunks":[{"header":"@@ -1 +1 @@","oldStart":1,"newStart":1,"lines":["-one\r"," two\r\r","+three"]}]}]}
      """,
    )

    val lines = loaded(parseGitChanges(reply)).diff.files.single().hunks.single().lines

    assertEquals(listOf("-one", " two\r", "+three"), lines)
  }

  @Test
  fun `an unchanged reply carries nothing else`() {
    assertSame(GitReply.Unchanged, parseGitChanges(JSONObject("""{"id":2,"fingerprint":"abc","unchanged":true}""")))
  }

  @Test
  fun `an error reply has only its error and code`() {
    val reply =
      parseGitChanges(
        JSONObject("""{"type":"pipkin_git_changes_result","id":3,"error":"fatal: bad","code":"failed"}"""),
      )

    val failed = reply as GitReply.Failed
    assertEquals(GitErrorCode.Failed, failed.code)
    assertEquals("fatal: bad", failed.message)
  }

  @Test
  fun `error codes map, and an unknown one counts as a plain failure`() {
    fun code(json: String) = (parseGitCommitDiff(JSONObject(json)) as GitReply.Failed).code

    assertEquals(GitErrorCode.NotRepo, code("""{"code":"not_repo","error":"not a repository"}"""))
    assertEquals(GitErrorCode.NoGit, code("""{"code":"no_git","error":""}"""))
    assertEquals(GitErrorCode.CommitNotFound, code("""{"code":"commit_not_found","error":"unknown revision"}"""))
    assertEquals(GitErrorCode.Failed, code("""{"code":"something_new","error":"?"}"""))
  }

  @Test
  fun `a diff too large to send has no files`() {
    val reply = parseGitCommitDiff(JSONObject("""{"tooLarge":true,"files":[]}"""))

    val diff = (reply as GitReply.Loaded).value
    assertTrue(diff.tooLarge)
    assertTrue(diff.files.isEmpty())
  }

  @Test
  fun `unknown change and kind names read as a modified text file`() {
    val reply =
      JSONObject("""{"files":[{"path":"a","change":"typechange","kind":"symlink","additions":0,"deletions":0}]}""")

    val file = (parseGitCommitDiff(reply) as GitReply.Loaded).value.files.single()

    assertEquals(GitChange.Modified, file.change)
    assertEquals(GitFileKind.Text, file.kind)
  }

  @Test
  fun `commits carry their total, which is never fewer than were listed`() {
    val reply = JSONObject(
      """
      {"fingerprint":"c","baseRef":"main","total":742,"commits":[
        {"sha":"0123456789abcdef","subject":"Add viewer","authorTime":1700000000}
      ]}
      """,
    )

    val commits = (parseGitCommits(reply) as GitReply.Loaded).value
    val commit = commits.commits.single()
    assertEquals(742, commits.total)
    assertEquals("0123456", commit.shortSha)
    assertEquals(1_700_000_000L, commit.authorTime)

    val withoutTotal = parseGitCommits(JSONObject("""{"fingerprint":"c","baseRef":null,"commits":[{"sha":"a"}]}"""))
    assertEquals(1, (withoutTotal as GitReply.Loaded).value.total)
  }
}
