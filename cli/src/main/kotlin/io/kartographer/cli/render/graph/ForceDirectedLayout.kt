package io.kartographer.cli.render.graph

import io.kartographer.core.model.Node
import io.kartographer.core.model.graph.AdjacencyList
import kotlin.math.*

class ForceDirectedLayout(
    val graph: AdjacencyList<Node, Unit>,
    val width: Int = 80,
    val height: Int = 20,
    val iterations: Int = 100
) {
    data class Point(var x: Double, var y: Double)
    data class Vector(var dx: Double, var dy: Double)

    val positions = mutableMapOf<Int, Point>()

    fun computeLayout(iterations: Int = 50) {
        if (graph.isEmpty) return

        val area = (width * height).toDouble()
        val numNodes = graph.size
        val k = sqrt(area / numNodes)

        // Initialize positions randomly
        val random = java.util.Random(42) // Fixed seed for stable layout
        for (i in graph.allVerticesIndices()) {
            positions[i] = Point(random.nextDouble() * width, random.nextDouble() * height)
        }

        var temperature = width / 10.0

        for (iter in 0 until iterations) {
            val displacements = mutableMapOf<Int, Point>()
            for (i in graph.allVerticesIndices()) {
                displacements[i] = Point(0.0, 0.0)
            }

            // Calculate repulsive forces
            for (v in graph.allVerticesIndices()) {
                for (u in graph.allVerticesIndices()) {
                    if (v != u) {
                        val deltaX = positions[v]!!.x - positions[u]!!.x
                        val deltaY = positions[v]!!.y - positions[u]!!.y
                        var dist = sqrt(deltaX * deltaX + deltaY * deltaY)
                        if (dist < 0.01) dist = 0.01 // prevent division by zero
                        
                        val repulse = (k * k) / dist
                        displacements[v]!!.x += (deltaX / dist) * repulse
                        displacements[v]!!.y += (deltaY / dist) * repulse
                    }
                }
            }

            // Calculate attractive forces (edges)
            for (v in graph.allVerticesIndices()) {
                val edges = graph.edgesFrom(v)
                for (u in edges.keys) {
                    val deltaX = positions[v]!!.x - positions[u]!!.x
                    val deltaY = positions[v]!!.y - positions[u]!!.y
                    var dist = sqrt(deltaX * deltaX + deltaY * deltaY)
                    if (dist < 0.01) dist = 0.01

                    val attract = (dist * dist) / k
                    displacements[v]!!.x -= (deltaX / dist) * attract
                    displacements[v]!!.y -= (deltaY / dist) * attract
                    displacements[u]!!.x += (deltaX / dist) * attract
                    displacements[u]!!.y += (deltaY / dist) * attract
                }
            }

            // Apply displacements
            for (v in graph.allVerticesIndices()) {
                val dispX = displacements[v]!!.x
                val dispY = displacements[v]!!.y
                val dispLen = sqrt(dispX * dispX + dispY * dispY)

                if (dispLen > 0) {
                    val limitX = (dispX / dispLen) * min(dispLen, temperature)
                    val limitY = (dispY / dispLen) * min(dispLen, temperature)
                    positions[v]!!.x += limitX
                    positions[v]!!.y += limitY
                }

                // Keep within bounds, with some padding
                val padX = 5.0
                val padY = 2.0
                positions[v]!!.x = max(padX, min(width - padX - 1, positions[v]!!.x))
                positions[v]!!.y = max(padY, min(height - padY - 1, positions[v]!!.y))
            }

            temperature *= 0.95
        }
    }

    fun renderEdgesToStrings(): List<String> {
        val buffer = Array(height) { CharArray(width) { ' ' } }

        // Draw edges
        for (v in graph.allVerticesIndices()) {
            val edges = graph.edgesFrom(v)
            for (u in edges.keys) {
                drawLine(
                    buffer,
                    positions[v]!!.x.roundToInt(), positions[v]!!.y.roundToInt(),
                    positions[u]!!.x.roundToInt(), positions[u]!!.y.roundToInt()
                )
            }
        }

        return buffer.map { String(it) }
    }

    private fun drawLine(buffer: Array<CharArray>, x0: Int, y0: Int, x1: Int, y1: Int) {
        var dx = abs(x1 - x0)
        var dy = abs(y1 - y0)
        var sx = if (x0 < x1) 1 else -1
        var sy = if (y0 < y1) 1 else -1
        var err = dx - dy

        var cx = x0
        var cy = y0

        val char = if (dx > dy) '-' else if (dx == 0) '|' else if (sx == sy) '\\' else '/'

        while (true) {
            if (cx in 0 until width && cy in 0 until height) {
                if (buffer[cy][cx] == ' ') {
                    buffer[cy][cx] = char
                }
            }
            if (cx == x1 && cy == y1) break
            var e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                cx += sx
            }
            if (e2 < dx) {
                err += dx
                cy += sy
            }
        }
    }
}
