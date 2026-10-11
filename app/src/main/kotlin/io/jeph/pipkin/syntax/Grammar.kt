package io.jeph.pipkin.syntax

import android.content.res.AssetManager
import java.util.concurrent.ConcurrentHashMap

/**
 * A grammar compiled into libpipkin-syntax, by its tree-sitter name [id], with the fence names and
 * file extensions that pick it. Its highlight query is the `syntax/<id>.scm` asset.
 */
internal enum class Grammar(val id: String, vararg val names: String) {
  BASH("bash", "bash", "sh", "shell", "zsh", "console"),
  C("c", "c", "h"),
  CPP("cpp", "cpp", "c++", "cc", "cxx", "hpp", "hh", "hxx"),
  C_SHARP("c_sharp", "csharp", "cs", "c#"),
  DART("dart", "dart"),
  GO("go", "go", "golang"),
  JAVA("java", "java"),
  JAVASCRIPT("javascript", "javascript", "js", "jsx", "mjs", "cjs"),
  JSON("json", "json", "jsonc"),
  KOTLIN("kotlin", "kotlin", "kt", "kts"),
  PERL("perl", "perl", "pl", "pm"),
  PHP("php", "php"),
  PYTHON("python", "python", "py", "python3", "pyi"),
  RUBY("ruby", "ruby", "rb"),
  RUST("rust", "rust", "rs"),
  SWIFT("swift", "swift"),
  TSX("tsx", "tsx"),
  TYPESCRIPT("typescript", "typescript", "ts", "mts", "cts"),
  XML("xml", "xml", "svg", "xsd", "xsl", "xslt", "plist"),
  ;

  companion object {
    private val byName = entries.flatMap { grammar -> grammar.names.map { it to grammar } }.toMap()

    /** The grammar for a fence's info string (`tsx title="App.tsx"`) or a file extension, or null. */
    fun forName(fence: String): Grammar? = byName[fence.trim().substringBefore(' ').lowercase()]
  }
}

/** A grammar's language and compiled highlight query, which every thread may share. */
internal class LoadedGrammar(
  val language: Long,
  val query: Long,
  val captureNames: List<String>,
  val patterns: List<List<Predicate>>,
)

private val loaded = ConcurrentHashMap<Grammar, LoadedGrammar>()

/** [grammar] with its highlight query read from [assets] and compiled, once per process. Slow the first time. */
internal fun loadGrammar(grammar: Grammar, assets: AssetManager): LoadedGrammar = loaded.computeIfAbsent(grammar) { _ ->
  val language = TreeSitter.language(grammar.id)
  val source = assets.open("syntax/${grammar.id}.scm").use { it.readBytes().decodeToString() }
  val query = TreeSitter.queryNew(language, source)
  val strings = TreeSitter.queryStrings(query).asList()
  LoadedGrammar(
    language = language,
    query = query,
    captureNames = TreeSitter.queryCaptureNames(query).asList(),
    patterns = List(TreeSitter.queryPatternCount(query)) { pattern ->
      parsePredicates(TreeSitter.queryPredicates(query, pattern), strings)
    },
  )
}

/**
 * The parse of one text as it changes: each [captures] reparses only what changed since the last text,
 * so a streamed reply costs about its new end. One thread at a time; [close] frees the native parse.
 */
internal class SyntaxSession(private val grammar: LoadedGrammar) : AutoCloseable {
  private val parser = TreeSitter.parserNew(grammar.language)
  private var tree = 0L
  private var text = ""

  /** [newText]'s highlight captures among those that [paints], as [captureSpans] gives them. */
  fun captures(newText: String, paints: (capture: Int) -> Boolean): List<CaptureSpan> {
    val oldTree = tree
    if (oldTree != 0L) TreeSitter.treeEdit(oldTree, textEdit(text, newText))
    tree = TreeSitter.parse(parser, oldTree, newText)
    text = newText
    if (oldTree != 0L) TreeSitter.treeDelete(oldTree)
    return captureSpans(newText, TreeSitter.matches(tree, grammar.query), grammar.patterns, paints)
  }

  override fun close() {
    if (tree != 0L) TreeSitter.treeDelete(tree)
    TreeSitter.parserDelete(parser)
  }
}
