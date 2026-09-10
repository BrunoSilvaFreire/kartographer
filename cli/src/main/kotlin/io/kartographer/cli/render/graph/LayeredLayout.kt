package io.kartographer.cli.render.graph

import io.kartographer.core.model.Node
import io.kartographer.core.model.graph.AdjacencyList
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A layered, left-to-right DAG layout.
 *
 * Nodes with no dependencies sit in the leftmost column, and each node is one column to the
 * right of its deepest dependency. Nodes are stacked vertically within their column, ordered
 * with a barycenter heuristic so that edges cross as little as possible.
 *
 * Spacing is derived from the available [maxWidth] and [maxHeight]: columns are given a
 * generous horizontal gap so edge routing is legible, and vertical gaps absorb the remaining
 * height. [nodeWidth] reports how many characters node labels should be truncated to.
 *
 * Edges are drawn orthogonally (horizontal run, vertical run, horizontal run) rather than as
 * diagonal slashes, which reads much more clearly at a glance.
 */
class LayeredLayout(
    val graph: AdjacencyList<Node, Unit>,
    val maxWidth: Int = 160,
    val maxHeight: Int = 40,
) {
    data class Point(var x: Double, var y: Double)

    /** Top-left corner of each node, keyed by vertex index. */
    val positions = mutableMapOf<Int, Point>()

    /** Dependency depth (column) of each vertex. */
    val levels = mutableMapOf<Int, Int>()

    /** Number of columns (levels). */
    var columnCount: Int = 0
        private set

    var nodeWidth: Int = 12
        private set

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    private var columnGap = 8
    private var rowGap = 2

    fun computeLayout() {
        if (graph.isEmpty) return

        // 1. Level assignment: level(v) = 1 + max(level(dependencies)).
        val level = mutableMapOf<Int, Int>()
        fun levelOf(v: Int): Int = level.getOrPut(v) {
            val deps = graph.edgesFrom(v).keys
            if (deps.isEmpty()) 0 else 1 + deps.maxOf { levelOf(it) }
        }
        for (v in graph.allVerticesIndices()) levelOf(v)

        val maxLevel = level.values.max()
        columnCount = maxLevel + 1
        levels.putAll(level)

        // 2. Order nodes within each column. Level 0 by name; deeper levels by the average
        //    position of their dependencies in the previous column (barycenter heuristic).
        val columns = Array(columnCount) { mutableListOf<Int>() }
        for (v in graph.allVerticesIndices()) columns[level[v]!!].add(v)

        columns[0].sortBy { graph.vertex(it)!!.name }
        for (l in 1 until columnCount) {
            val prevIndex = columns[l - 1].mapIndexed { index, v -> v to index }.toMap()
            columns[l].sortWith(
                compareBy({ barycenter(it, prevIndex) }, { graph.vertex(it)!!.name })
            )
        }

        val maxNodesInColumn = columns.maxOf { it.size }

        // 3. Derive spacing from available width and height.
        columnGap = (maxWidth / (columnCount + 1)).coerceIn(8, 16)
        nodeWidth = ((maxWidth - columnGap * (columnCount - 1)) / columnCount)
            .coerceIn(6, 20)
        rowGap = (maxHeight / max(1, maxNodesInColumn) - 1).coerceIn(2, 6)

        val colWidth = nodeWidth + columnGap
        val rowHeight = 1 + rowGap

        // 4. Assign positions.
        for (l in 0 until columnCount) {
            columns[l].forEachIndexed { row, v ->
                positions[v] = Point((l * colWidth).toDouble(), (row * rowHeight).toDouble())
            }
        }

        width = columnCount * colWidth - columnGap
        height = maxNodesInColumn * rowHeight - rowGap
    }

    private fun barycenter(v: Int, prevIndex: Map<Int, Int>): Double {
        val depsInPrev = graph.edgesFrom(v).keys.filter { it in prevIndex }
        return if (depsInPrev.isEmpty()) Double.MAX_VALUE else depsInPrev.map { prevIndex.getValue(it) }.average()
    }

    /**
     * Renders edges as an orthogonal ASCII grid.
     *
     * @param highlightVertices when null, every edge is drawn; otherwise only edges incident to
     *   one of these vertices are drawn (used to render a bright overlay for the selection).
     */
    fun renderEdgesToStrings(highlightVertices: Set<Int>? = null): List<String> {
        val buffer = Array(height) { CharArray(width) { ' ' } }
        for (dependent in graph.allVerticesIndices()) {
            for (dependency in graph.edgesFrom(dependent).keys) {
                if (highlightVertices != null &&
                    dependent !in highlightVertices &&
                    dependency !in highlightVertices
                ) {
                    continue
                }
                drawEdge(buffer, dependency, dependent)
            }
        }
        return buffer.map { String(it) }
    }

    private fun drawEdge(buffer: Array<CharArray>, dependency: Int, dependent: Int) {
        val from = positions[dependency] ?: return
        val to = positions[dependent] ?: return

        val fromX = (from.x + nodeWidth).roundToInt()
        val fromY = from.y.roundToInt()
        val toX = to.x.roundToInt()
        val toY = to.y.roundToInt()
        val midX = (fromX + toX) / 2

        hLine(buffer, fromX, midX, fromY)
        vLine(buffer, midX, fromY, toY)
        hLine(buffer, midX, toX, toY)
        corner(buffer, midX, fromY)
        corner(buffer, midX, toY)
    }

    private fun hLine(buffer: Array<CharArray>, x0: Int, x1: Int, y: Int) {
        if (y !in 0 until height) return
        val lo = minOf(x0, x1)
        val hi = maxOf(x0, x1)
        for (x in lo..hi) {
            if (x in 0 until width && buffer[y][x] == ' ') buffer[y][x] = '-'
        }
    }

    private fun vLine(buffer: Array<CharArray>, x: Int, y0: Int, y1: Int) {
        if (x !in 0 until width) return
        val lo = minOf(y0, y1)
        val hi = maxOf(y0, y1)
        for (y in lo..hi) {
            if (y in 0 until height && buffer[y][x] == ' ') buffer[y][x] = '|'
        }
    }

    private fun corner(buffer: Array<CharArray>, x: Int, y: Int) {
        if (y in 0 until height && x in 0 until width) buffer[y][x] = '+'
    }
}
