package io.kartographer.core.scheduler

import io.kartographer.core.Task
import io.kartographer.core.TaskId

class MissingDependencyException(val taskName: String, val dependencyName: String) :
    RuntimeException("Task '$taskName' depends on '$dependencyName', which does not exist.")

class CycleDetectedException(val cycle: List<String>) :
    RuntimeException("Cycle detected in task dependencies: ${cycle.joinToString(" -> ")}")

class DagScheduler(private val tasks: Map<TaskId, Task>) {

    // Resolve dependencies for all tasks and detect missing dependencies
    fun resolveDependencies(): Map<Task, Set<Task>> {
        val resolved = mutableMapOf<Task, Set<Task>>()
        for (task in tasks.values) {
            val deps = mutableSetOf<Task>()
            // Add direct object dependencies
            deps.addAll(task.dependencyObjects)

            // Resolve name-based dependencies
            for (depName in task.dependencyNames) {
                val depId = TaskId(depName)
                val depTask = tasks[depId] ?: throw MissingDependencyException(task.name, depName)
                deps.add(depTask)
            }

            // Resolve property-based implicit dependencies
            for (prop in task.registeredProperties) {
                prop.producingTask?.let { producer ->
                    deps.add(producer)
                }
            }
            resolved[task] = deps
        }
        return resolved
    }

    // Returns a list of tasks in topological order (execution order)
    fun getExecutionOrder(): List<Task> {
        val adjacencyMap = resolveDependencies()
        val visited = mutableMapOf<Task, VisitState>()
        val result = mutableListOf<Task>()

        // Helper function for DFS
        fun dfs(task: Task, path: MutableList<Task>) {
            val state = visited[task]
            if (state == VisitState.VISITING) {
                val cycleStartIndex = path.indexOf(task)
                val cyclePath = (path.subList(cycleStartIndex, path.size) + task).map { it.name }
                throw CycleDetectedException(cyclePath)
            }
            if (state == VisitState.VISITED) {
                return
            }

            visited[task] = VisitState.VISITING
            path.add(task)

            val deps = adjacencyMap[task] ?: emptySet()
            for (dep in deps) {
                dfs(dep, path)
            }

            path.removeAt(path.size - 1)
            visited[task] = VisitState.VISITED
            result.add(task)
        }

        for (task in tasks.values) {
            if (visited[task] == null) {
                dfs(task, mutableListOf())
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
