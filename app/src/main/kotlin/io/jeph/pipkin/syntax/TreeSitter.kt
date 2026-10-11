package io.jeph.pipkin.syntax

/**
 * tree-sitter's C API through libpipkin-syntax (`app/src/main/cpp/syntax.c`). Handles are native
 * pointers, 0 for none; text offsets are Java char indices.
 */
internal object TreeSitter {
  init {
    System.loadLibrary("pipkin-syntax")
  }

  /** The grammar compiled in under [name], or 0. */
  @JvmStatic external fun language(name: String): Long

  @JvmStatic external fun parserNew(language: Long): Long

  @JvmStatic external fun parserDelete(parser: Long)

  /** A new tree for [text]; [oldTree], already edited to match [text], lets unchanged parts be reused. */
  @JvmStatic external fun parse(parser: Long, oldTree: Long, text: String): Long

  /** Applies a [textEdit] to [tree] before it is reparsed. */
  @JvmStatic external fun treeEdit(tree: Long, edit: IntArray)

  @JvmStatic external fun treeDelete(tree: Long)

  /** A compiled query; throws IllegalArgumentException naming the error and its offset. */
  @JvmStatic external fun queryNew(language: Long, source: String): Long

  @JvmStatic external fun queryCaptureNames(query: Long): Array<String>

  @JvmStatic external fun queryStrings(query: Long): Array<String>

  @JvmStatic external fun queryPatternCount(query: Long): Int

  /** [pattern]'s predicate steps as (type, value id) pairs; see [parsePredicates]. */
  @JvmStatic external fun queryPredicates(query: Long, pattern: Int): IntArray

  /** Every match of [query] in [tree], laid out as [captureSpans] reads them. */
  @JvmStatic external fun matches(tree: Long, query: Long): IntArray
}
