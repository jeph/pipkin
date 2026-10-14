package io.jeph.pipkin.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {
  @Test
  fun frontMatterBecomesRowsAndLeavesTheBody() {
    val source = "---\nname: pdf\ndescription: \"Reads: PDFs\"\n---\n# Title\n"
    assertEquals(
      MarkdownDocument(listOf("name" to "pdf", "description" to "Reads: PDFs"), "# Title\n"),
      parseMarkdownDocument(source),
    )
  }

  @Test
  fun valuesReadAsPaseoShowsThem() {
    val source = """
      |---
      |tags: [a, 'b', "c"]
      |list:
      |  - one
      |  - two
      |empty:
      |tilde: ~
      |count: 3
      |url: https://example.com/a:b
      |folded: first
      |  second
      |literal: |
      |  line one
      |  line two
      |nested:
      |  key: value
      |  other: 2
      |'quoted key': 'it''s'
      |---
      |body
    """.trimMargin()
    assertEquals(
      listOf(
        "tags" to "a, b, c",
        "list" to "one, two",
        "empty" to "null",
        "tilde" to "null",
        "count" to "3",
        "url" to "https://example.com/a:b",
        "folded" to "first second",
        "literal" to "line one\nline two",
        "nested" to "key: value\nother: 2",
        "quoted key" to "it's",
      ),
      parseMarkdownDocument(source).frontMatter,
    )
  }

  @Test
  fun aHashIsACommentOnlyOutsideQuotesAfterWhitespace() {
    val source = """
      |---
      |double: "Release #1" # note
      |single: 'it''s #2'
      |escaped: "say \"hi\" #3"
      |plain: issue#4 # trailing
      |---
      |body
    """.trimMargin()
    assertEquals(
      MarkdownDocument(
        listOf(
          "double" to "Release #1",
          "single" to "it's #2",
          "escaped" to "say \\\"hi\\\" #3",
          "plain" to "issue#4",
        ),
        "body",
      ),
      parseMarkdownDocument(source),
    )
  }

  @Test
  fun aBlockThatIsNotAMappingStaysInTheBody() {
    for (source in listOf("---\n- a\n- b\n---\nbody", "---\njust text\n---\nbody", "---\na: 1\na: 2\n---\nbody")) {
      assertEquals(MarkdownDocument(emptyList(), source), parseMarkdownDocument(source))
    }
  }

  @Test
  fun onlyAFenceOnTheFirstLineIsFrontMatter() {
    for (source in listOf("# Title\n---\na: 1\n---\n", "---\na: 1\n", "")) {
      assertEquals(MarkdownDocument(emptyList(), source), parseMarkdownDocument(source))
    }
    assertEquals(listOf("a" to "1"), parseMarkdownDocument("\uFEFF---\r\na: 1\r\n---\r\nbody").frontMatter)
    assertEquals("", parseMarkdownDocument("---\na: 1\n---").body)
  }

  @Test
  fun crlfAndLoneCrBecomeLf() {
    assertEquals(
      MarkdownDocument(listOf("a" to "1"), "# Title\nline one\nline two\n"),
      parseMarkdownDocument("---\r\na: 1\r\n---\r\n# Title\r\nline one\rline two\r\n"),
    )
    assertEquals(MarkdownDocument(emptyList(), "plain\n\nbody"), parseMarkdownDocument("plain\r\n\r\nbody"))
  }

  @Test
  fun markdownIsMdOrMarkdownInAnyCase() {
    assertTrue(isMarkdownFile("README.md"))
    assertTrue(isMarkdownFile("notes.MARKDOWN"))
    assertFalse(isMarkdownFile("notes.mdx"))
    assertFalse(isMarkdownFile("md"))
  }
}
