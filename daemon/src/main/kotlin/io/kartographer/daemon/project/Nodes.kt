package io.kartographer.daemon.project

import io.kartographer.core.model.Node
import io.kartographer.core.model.TaskId

object Nodes {
    /**
     * [target] plus everything it transitively depends on.
     *
     * Dependencies come in two forms — direct object references and names resolved lazily
     * against the registry ([Node.dependencyObjects] / [Node.dependencyNames]) — and both have
     * to be followed. A name with no matching node is skipped here; the DAG scheduler is what
     * reports it as a missing dependency.
     */
    fun closure(target: Node, all: Map<TaskId, Node>): Set<Node> {
        val seen = linkedSetOf<Node>()

        fun visit(node: Node) {
            if (!seen.add(node)) return
            node.dependencyObjects.forEach { visit(it) }
            node.dependencyNames.forEach { name -> all[TaskId(name)]?.let { visit(it) } }
        }

        visit(target)
        return seen
    }
}
