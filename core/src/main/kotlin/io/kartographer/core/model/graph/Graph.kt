package io.kartographer.core.model.graph

interface Graph<V, E> {
    val size: Int
    val isEmpty: Boolean

    fun vertex(index: Int): V?
    fun edge(from: Int, to: Int): E?
    fun connect(from: Int, to: Int, edge: E)
    fun disconnect(from: Int, to: Int): Boolean
}
