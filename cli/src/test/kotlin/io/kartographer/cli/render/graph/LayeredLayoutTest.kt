package io.kartographer.cli.render.graph

import io.kartographer.core.model.Node
import io.kartographer.core.model.graph.AdjacencyList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class TestNode(name: String) : Node(name)

class LayeredLayoutTest {

    private fun graph(vararg deps: Pair<String, String>): AdjacencyList<Node, Unit> {
        val g = AdjacencyList<Node, Unit>()
        val indices = mutableMapOf<String, Int>()
        fun indexOf(name: String): Int = indices.getOrPut(name) { g.push(TestNode(name)) }
        for ((from, to) in deps) {
            g.connect(indexOf(from), indexOf(to), Unit)
        }
        return g
    }

    @Test
    fun `dependencies sit to the left of dependents`() {
        val g = graph("c" to "b", "b" to "a")
        val layout = LayeredLayout(g).apply { computeLayout() }

        val byName = g.allVerticesIndices().associate { g.vertex(it)!!.name to layout.positions[it]!! }

        assertTrue(byName["a"]!!.x < byName["b"]!!.x)
        assertTrue(byName["b"]!!.x < byName["c"]!!.x)
    }

    @Test
    fun `layout fits within max width`() {
        val g = graph("d" to "c", "c" to "b", "b" to "a")
        val layout = LayeredLayout(g, maxWidth = 60).apply { computeLayout() }

        assertTrue(layout.width <= 60, "width ${layout.width} exceeds max width")
        val edges = layout.renderEdgesToStrings()
        assertEquals(layout.height, edges.size)
        assertTrue(edges.all { it.length == layout.width })
    }

    @Test
    fun `nodes at the same depth share a column`() {
        // a and b are both roots (level 0); c depends on both.
        val g = graph("c" to "a", "c" to "b")
        val layout = LayeredLayout(g).apply { computeLayout() }

        val byName = g.allVerticesIndices().associate { g.vertex(it)!!.name to layout.positions[it]!! }
        assertEquals(byName["a"]!!.x, byName["b"]!!.x)
        assertTrue(byName["c"]!!.x > byName["a"]!!.x)
    }
}
