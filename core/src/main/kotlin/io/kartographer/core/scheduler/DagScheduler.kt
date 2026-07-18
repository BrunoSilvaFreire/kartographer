package io.kartographer.core.scheduler

import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId

class MissingDependencyException(val nodeName: String, val dependencyName: String) :
    RuntimeException("Node '$nodeName' depends on '$dependencyName', which does not exist.")

class CycleDetectedException(val cycle: List<String>) :
    RuntimeException("Cycle detected in dependencies: ${cycle.joinToString(" -> ")}")

class DagScheduler(private val nodes: Map<TaskId, Node>) {

    // Resolve dependencies for all nodes and detect missing dependencies
    fun resolveDependencies(): Map<Node, Set<Node>> {
        val resolved = mutableMapOf<Node, Set<Node>>()
        for (node in nodes.values) {
            val deps = mutableSetOf<Node>()
            // Add direct object dependencies
            deps.addAll(node.dependencyObjects)

            // Resolve name-based dependencies
            for (depName in node.dependencyNames) {
                val depId = TaskId(depName)
                val depNode = nodes[depId] ?: throw MissingDependencyException(node.name, depName)
                deps.add(depNode)
            }

            // Resolve property-based implicit dependencies
            if (node is Task) {
                for (prop in node.registeredProperties) {
                    prop.producingTask?.let { producer ->
                        deps.add(producer)
                    }
                }
            }
            resolved[node] = deps
        }
        return resolved
    }

    // Returns a list of nodes in topological order (execution order)
    fun getExecutionOrder(): List<Node> {
        val adjacencyMap = resolveDependencies()
        val visited = mutableMapOf<Node, VisitState>()
        val result = mutableListOf<Node>()

        // Helper function for DFS
        fun dfs(node: Node, path: MutableList<Node>) {
            val state = visited[node]
            if (state == VisitState.VISITING) {
                val cycleStartIndex = path.indexOf(node)
                val cyclePath = (path.subList(cycleStartIndex, path.size) + node).map { it.name }
                throw CycleDetectedException(cyclePath)
            }
            if (state == VisitState.VISITED) {
                return
            }

            visited[node] = VisitState.VISITING
            path.add(node)

            val deps = adjacencyMap[node] ?: emptySet()
            for (dep in deps) {
                dfs(dep, path)
            }

            path.removeAt(path.size - 1)
            visited[node] = VisitState.VISITED
            result.add(node)
        }

        for (node in nodes.values) {
            if (visited[node] == null) {
                dfs(node, mutableListOf())
            }
        }

        // The DFS post-order gives a reverse topological sort, so we reverse it.
        // Wait, if A depends on B, we visit A, then visit B. B finishes first, so B is added first, then A.
        // So B (dependency) is before A (dependent).
        // Let's verify:
        // A depends on B (A -> B).
        // dfs(A) -> dfs(B) -> B finishes, added to result [B] -> A finishes, added to result [B, A].
        // This is exactly the execution order (B must execute before A)!
        // So we do NOT need to reverse it if we add to the end of the list during post-order dfs.
        // Let's double check.
        // If B has no dependencies, dfs(B) completes, result is [B].
        // Then dfs(A) completes, result is [B, A].
        // So B is executed first, which is correct!
        return result
    }

    private enum class VisitState {
        VISITING,
        VISITED
    }
}
