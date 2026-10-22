package io.jeph.pipkin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import io.jeph.pipkin.ui.theme.PipkinTokens
import io.jeph.pipkin.ui.theme.pipkinColors
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * A markdown table on a raised card: a muted bold header and a line under each row. Unlike the renderer's own
 * table, whose columns share the width equally and cut each cell to one line, each column here is sized to what it
 * holds (see [columnWidths]) and cells wrap. The table scrolls sideways only when its columns can't fit at their
 * narrowest readable width.
 */
@Composable
internal fun MarkdownTable(content: String, node: ASTNode, style: TextStyle) {
  val table = remember(content, node) { parseTable(content, node) }
  if (table.columns == 0) return
  val colors = pipkinColors
  val shape = RoundedCornerShape(16.dp)
  val annotator = annotatorSettings()
  val headerStyle = style.copy(color = colors.foregroundMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
  val rows = listOf(table.header) + table.body
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val available = constraints.maxWidth
    val measurePolicy = remember(rows.size, table.columns, available) {
      gridMeasurePolicy(rows.size, table.columns, available)
    }
    Layout(
      contents = listOf(
        {
          rows.forEachIndexed { rowIndex, cells ->
            cells.forEachIndexed { columnIndex, cell ->
              val cellStyle = (if (rowIndex == 0) headerStyle else style).copy(textAlign = table.aligns[columnIndex])
              TableCell(content, cell, cellStyle, annotator, rowIndex, columnIndex)
            }
          }
        },
        { repeat(rows.size - 1) { Box(Modifier.background(colors.surface2)) } },
      ),
      modifier = Modifier
        .horizontalScroll(rememberScrollState())
        .clip(shape)
        .background(colors.surface1)
        .semantics { collectionInfo = CollectionInfo(rows.size, table.columns) },
      measurePolicy = measurePolicy,
    )
  }
}

/** A cell's text, taking the column's whole width so its alignment has room to apply; an empty cell holds only padding. */
@Composable
private fun TableCell(
  content: String,
  cell: ASTNode?,
  style: TextStyle,
  annotator: AnnotatorSettings,
  rowIndex: Int,
  columnIndex: Int,
) {
  Box(
    modifier = Modifier
      .padding(horizontal = CELL_PADDING_HORIZONTAL, vertical = CELL_PADDING_VERTICAL)
      .semantics {
        if (rowIndex == 0) heading()
        collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1)
      },
    propagateMinConstraints = true,
  ) {
    if (cell == null) return@Box
    MarkdownTableBasicText(
      content = content,
      cell = cell,
      style = style,
      maxLines = Int.MAX_VALUE,
      overflow = TextOverflow.Clip,
      annotatorSettings = annotator,
    )
  }
}

/**
 * Lays out the cells row by row in columns sized by [columnWidths] for [available], each row as tall as its tallest
 * cell, then the lines between rows.
 */
private fun gridMeasurePolicy(rows: Int, columns: Int, available: Int) =
  MultiContentMeasurePolicy { (cells, lines), _ ->
    val minimums = IntArray(columns)
    val naturals = IntArray(columns)
    cells.forEachIndexed { index, cell ->
      val column = index % columns
      minimums[column] = maxOf(minimums[column], cell.minIntrinsicWidth(Constraints.Infinity))
      naturals[column] = maxOf(naturals[column], cell.maxIntrinsicWidth(Constraints.Infinity))
    }
    val widths = columnWidths(minimums, naturals, available, COLUMN_FLOOR.roundToPx(), WORD_CAP.roundToPx())
    val placed = cells.mapIndexed { index, cell -> cell.measure(Constraints.fixedWidth(widths[index % columns])) }
    val heights = IntArray(rows) { row -> (0 until columns).maxOf { placed[row * columns + it].height } }
    val lefts = widths.runningFold(0, Int::plus)
    val tops = heights.toList().runningFold(0, Int::plus)
    val width = lefts.last()
    val height = tops.last()
    val line = GRID_LINE.roundToPx()
    val rowLines = (1 until rows).map { lines[it - 1].measure(Constraints.fixed(width, line)) }
    layout(width, height) {
      placed.forEachIndexed { index, cell -> cell.place(lefts[index % columns], tops[index / columns]) }
      rowLines.forEachIndexed { index, rowLine -> rowLine.place(0, tops[index + 1]) }
    }
  }

/**
 * Column widths for cells whose longest words need [minimums] and whose text on one line needs [naturals]. Columns
 * that all fit on one line in [available] take just that, so a small table stays small. Otherwise each column may
 * narrow to its floor, its longest word but at least [floor] (no more than it needs, and with a word of over [wordCap]
 * broken), and the room left over goes to the columns in proportion to how much more they would take, so short
 * columns keep their text on one line and long ones share the rest. When even the floors don't fit, the columns take
 * their floors and the table scrolls.
 */
internal fun columnWidths(minimums: IntArray, naturals: IntArray, available: Int, floor: Int, wordCap: Int): IntArray {
  val natural = IntArray(naturals.size) { maxOf(minimums[it], naturals[it]) }
  if (natural.sum() <= available) return natural
  val floors = IntArray(natural.size) { minOf(natural[it], maxOf(minOf(minimums[it], wordCap), floor)) }
  val spare = available - floors.sum()
  if (spare <= 0) return floors
  val wants = IntArray(natural.size) { natural[it] - floors[it] }
  val wanted = wants.sum().toLong()
  val widths = IntArray(natural.size) { floors[it] + (spare * wants[it].toLong() / wanted).toInt() }
  widths[wants.indices.maxBy { wants[it] }] += available - widths.sum()
  return widths
}

/** A table's cells by row, each row padded or cut to the header's columns as GFM reads them, and its alignments. */
private class ParsedTable(val header: List<ASTNode?>, val body: List<List<ASTNode?>>, val aligns: List<TextAlign>) {
  val columns: Int get() = header.size
}

private fun parseTable(content: String, node: ASTNode): ParsedTable {
  val header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.cells().orEmpty()
  val columns = header.size
  val body = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
    val cells = row.cells()
    List(columns) { cells.getOrNull(it) }
  }
  val delimiter = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.getTextInNode(content)
  val markers = delimiter?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
  val aligns = List(columns) { alignment(markers.getOrNull(it)) }
  return ParsedTable(header, body, aligns)
}

private fun ASTNode.cells(): List<ASTNode> = children.filter { it.type == GFMTokenTypes.CELL }

/** GFM's delimiter row: `:---:` centers a column, `---:` puts it at the end, and anything else at the start. */
private fun alignment(marker: String?): TextAlign = when {
  marker == null -> TextAlign.Start
  marker.length > 1 && marker.startsWith(':') && marker.endsWith(':') -> TextAlign.Center
  marker.endsWith(':') -> TextAlign.End
  else -> TextAlign.Start
}

private val GRID_LINE = 1.dp

private val CELL_PADDING_HORIZONTAL = PipkinTokens.Spacing.s12

private val CELL_PADDING_VERTICAL = PipkinTokens.Spacing.s8

/** The narrowest a column of long text is squeezed to before the table scrolls instead. */
private val COLUMN_FLOOR = 120.dp

/** The widest a single unbreakable word, such as a path or URL, holds its column open before it breaks. */
private val WORD_CAP = 200.dp
