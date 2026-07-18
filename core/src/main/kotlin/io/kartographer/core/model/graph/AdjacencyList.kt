package io.kartographer.core.model.graph

class AdjacencyList<V, E> : Graph<V, E> {
    class AdjacencyNode<V, E>(var data: V?) {
        val connections = mutableMapOf<Int, E>()
        
        fun edge(to: Int): E? = connections[to]
        
        fun connect(to: Int, edge: E) {
            connections[to] = edge
        }
        
        fun disconnect(to: Int): Boolean {
            return connections.remove(to) != null
        }
        
        fun clear() {
            connections.clear()
            data = null
        }
    }

    private val nodes = mutableListOf<AdjacencyNode<V, E>>()
    private val freeIndices = java.util.ArrayDeque<Int>()
    private val freeIndicesSet = mutableSetOf<Int>()

    override val size: Int
        get() = nodes.size - freeIndices.size

    override val isEmpty: Boolean
        get() = size == 0

    fun push(vertex: V): Int {
        if (freeIndices.isNotEmpty()) {
            val index = freeIndices.removeFirst()
            freeIndicesSet.remove(index)
            nodes[index].data = vertex
            return index
        } else {
            val index = nodes.size
            nodes.add(AdjacencyNode(vertex))
            return index
        }
    }

    fun remove(index: Int) {
        nodes[index].clear()
        freeIndices.addLast(index)
        freeIndicesSet.add(index)
    }

    override fun vertex(index: Int): V? {
        if (index in freeIndicesSet || index >= nodes.size) return null
        return nodes[index].data
    }

    override fun edge(from: Int, to: Int): E? {
        if (from in freeIndicesSet || from >= nodes.size) return null
        return nodes[from].edge(to)
    }

    override fun connect(from: Int, to: Int, edge: E) {
        if (from in freeIndicesSet || from >= nodes.size) return
        nodes[from].connect(to, edge)
    }

    override fun disconnect(from: Int, to: Int): Boolean {
        if (from in freeIndicesSet || from >= nodes.size) return false
        return nodes[from].disconnect(to)
    }

    fun allVerticesIndices(): List<Int> {
        return nodes.indices.filter { it !in freeIndicesSet }
    }

    fun edgesFrom(index: Int): Map<Int, E> {
        if (index in freeIndicesSet || index >= nodes.size) return emptyMap()
        return nodes[index].connections.toMap()
    }
}
