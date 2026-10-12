package io.jeph.pipkin.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialJsonTest {
  private val full =
    """{"path": "src/a.py", "content": "def f():\n\t# \"hi\" \u00e9 \ud83d\ude00\n\treturn 1\\2",""" +
      """ "edits": [{"oldText": "a", "newText": "b"}, {"oldText": "c"}], "count": 12345, "ratio": -1.5e2,""" +
      """ "force": true, "dry": false, "note": null}"""

  @Test
  fun everyPrefixKeepsOnlyWhatHasArrived() {
    val expected = JSONObject(full)
    val content = expected.getString("content")
    for (end in 1..full.length) {
      val args = parsePartialJsonObject(full.substring(0, end)) ?: error("no object at $end")
      // A string grows as it streams; a key, number or literal only appears once complete.
      args.optString("content").let { assertTrue("content at $end", content.startsWith(it)) }
      if (args.has("count")) assertEquals(12345L, args.getLong("count"))
      if (args.has("ratio")) assertEquals(-150.0, args.getDouble("ratio"), 0.0)
      if (args.has("force")) assertTrue(args.getBoolean("force"))
      if (args.has("dry")) assertFalse(args.getBoolean("dry"))
      for (key in args.keys()) assertTrue("key $key at $end", expected.has(key))
    }
    val parsed = parsePartialJsonObject(full) ?: error("no object")
    val keys = expected.keys().asSequence().toSet()
    assertEquals(keys, parsed.keys().asSequence().toSet())
    // The loop already checked ratio's value; json.org's test JSONObject keeps its text form (-1.5E+2).
    for (key in keys - "ratio") assertEquals(key, expected.get(key).toString(), parsed.get(key).toString())
  }

  @Test
  fun aKeyWaitingForItsValueIsLeftOut() {
    assertEquals("{}", parsePartialJsonObject("""{"path"""").toString())
    assertEquals("{}", parsePartialJsonObject("""{"path": """).toString())
    assertEquals("""{"path":""}""", parsePartialJsonObject("""{"path": """").toString())
    assertEquals("""{"a":[1]}""", parsePartialJsonObject("""{"a": [1, 2""").toString())
  }

  @Test
  fun textThatIsNotAnObjectGivesNull() {
    assertNull(parsePartialJsonObject(""))
    assertNull(parsePartialJsonObject("  "))
    assertNull(parsePartialJsonObject("""["a"]"""))
  }

  @Test
  fun malformedTextKeepsWhatParsedBeforeIt() {
    assertEquals("""{"a":1}""", parsePartialJsonObject("""{"a": 1, "b": nope, "c": 2}""").toString())
    assertEquals("""{"a":1}""", parsePartialJsonObject("""{"a": 1, "b": 1e999}""").toString())
    assertEquals("{}", parsePartialJsonObject("""{"a" 1}""").toString())
  }

  @Test
  fun lastKeyIsTheTopLevelKeyReadLast() {
    assertEquals("content", readPartialJsonObject("""{"path": "a", "content": "de""")?.lastKey)
    assertEquals("path", readPartialJsonObject("""{"path": "a", "cont""")?.lastKey)
    // Keys inside a nested value do not count: only a top-level value can still be growing at the end.
    assertEquals("edits", readPartialJsonObject("""{"path": "a", "edits": [{"oldText": "x""")?.lastKey)
    assertNull(readPartialJsonObject("{")?.lastKey)
  }
}
