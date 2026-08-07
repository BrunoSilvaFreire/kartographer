package io.kartographer.core.scheduler

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionObserver
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.kartographer.core.model.graph.AdjacencyList
import java.util.concurrent.ConcurrentHashMap

class ConcurrentScheduler(private val allNodes: Map<TaskId, Node>) {

    suspend fun execute(
        targetNode: Node,
        context: ExecutionContext,
        checkTopology: (Node) -> Boolean
    ): ExecutionResult = supervisorScope {
        val dagScheduler = DagScheduler(allNodes)
        val resolvedDeps = dagScheduler.resolveDependencies()

        // 1. Collect target node and its transitive dependencies
        val executionSet = mutableSetOf<Node>()
        fun collect(n: Node) {
            if (executionSet.add(n)) {
                val deps = resolvedDeps[n] ?: emptySet()
                for (d in deps) {
                    collect(d)
                }
            }
        }
        collect(targetNode)

        // Build AdjacencyList graph
        val graph = AdjacencyList<Node, Unit>()
        val nodeIndices = mutableMapOf<Node, Int>()
        for (node in executionSet) {
            nodeIndices[node] = graph.push(node)
        }
        for (node in executionSet) {
            val fromIndex = nodeIndices[node]!!
            val deps = resolvedDeps[node] ?: emptySet()
            for (dep in deps) {
                if (dep in executionSet) {
                    val toIndex = nodeIndices[dep]!!
                    graph.connect(fromIndex, toIndex, Unit)
                }
            }
        }

        context.observer?.onEvent(ExecutionEvent.PipelineStarted(executionSet.size, graph))

        // 2. Map nodes to their remaining dependencies within this execution set
        val remainingDeps = ConcurrentHashMap<Node, MutableSet<Node>>()
        for (node in executionSet) {
            val deps = (resolvedDeps[node] ?: emptySet()).filter { it in executionSet }
            remainingDeps[node] = deps.toMutableSet()
            context.observer?.onEvent(ExecutionEvent.TaskQueued(node))
        }

        val mutex = Mutex()
        val runningJobs = ConcurrentHashMap<Node, Job>()
        val completedNodes = ConcurrentHashMap.newKeySet<Node>()
        var failedNode: Node? = null
        var failureResult: ExecutionResult.Failure? = null

        val scope = this

        fun checkAndSpawnReady() {
            scope.launch {
                mutex.withLock {
                    if (failedNode != null) return@withLock

                    for (node in executionSet) {
                        if (node !in completedNodes && node !in runningJobs.keys) {
                            val deps = remainingDeps[node] ?: emptySet()
                            if (deps.isEmpty()) {
                                context.observer?.onEvent(ExecutionEvent.TaskStarted(node))

                                val job = scope.launch nodeJob@{
                                    // Check topology placement requirements
                                    if (!checkTopology(node)) {
                                        val err = RuntimeException("Node '${node.name}' topology requirements not satisfied.")
                                        val result = ExecutionResult.Failure(err, 1)
                                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputsOf(node)))
                                        mutex.withLock {
                                            if (failedNode == null) {
                                                failedNode = node
                                                failureResult = result
                                            }
                                        }
                                        cancelAllJobs(runningJobs.values)
                                        return@nodeJob
                                    }

                                    if (node !is Task) {
                                        val result = ExecutionResult.Success
                                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputsOf(node)))
                                        mutex.withLock {
                                            completedNodes.add(node)
                                            for (otherNode in executionSet) {
                                                remainingDeps[otherNode]?.remove(node)
                                            }
                                        }
                                        checkAndSpawnReady()
                                        return@nodeJob
                                    }

                                    val taskContext = context.copy(
                                        task = node,
                                        prompter = io.kartographer.local.ConsoleUserPrompter(task = node, observer = context.observer)
                                    )
                                    val finalContext = taskContext.copy(
                                        executionEnvironment = context.executionEnvironment ?: io.kartographer.local.HostExecutionEnvironment(taskContext)
                                    )

                                    // Check if up-to-date
                                    if (node.isUpToDate(finalContext)) {
                                        // Trigger callback to inform runner it is up-to-date
                                        val result = ExecutionResult.UpToDate
                                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputsOf(node)))
                                        mutex.withLock {
                                            completedNodes.add(node)
                                            for (otherNode in executionSet) {
                                                remainingDeps[otherNode]?.remove(node)
                                            }
                                        }
                                        checkAndSpawnReady()
                                        return@nodeJob
                                    }

                                    // Execute task. Custom task types are not required to
                                    // catch their own exceptions, so convert an uncaught
                                    // task failure into the same fail-fast result used by
                                    // tasks that return ExecutionResult.Failure.
                                    val result = try {
                                        node.execute(finalContext)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Throwable) {
                                        ExecutionResult.Failure(error, 1)
                                    }
                                    when (result) {
                                        is ExecutionResult.Success -> {
                                            TaskStateStore.saveState(finalContext, node)
                                            context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputsOf(node)))
                                            mutex.withLock {
                                                completedNodes.add(node)
                                                for (otherNode in executionSet) {
                                                    remainingDeps[otherNode]?.remove(node)
                                                }
                                            }
                                            checkAndSpawnReady()
                                        }
                                        is ExecutionResult.UpToDate -> {
                                            context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputsOf(node)))
                                            mutex.withLock {
                                                completedNodes.add(node)
                                                for (otherNode in executionSet) {
                                                    remainingDeps[otherNode]?.remove(node)
                                                }
                                            }
                                            checkAndSpawnReady()
                                        }
                                        is ExecutionResult.Failure -> {
                                            context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputsOf(node)))
                                            mutex.withLock {
                                                if (failedNode == null) {
                                                    failedNode = node
                                                    failureResult = result
                                                }
                                            }
                                            cancelAllJobs(runningJobs.values)
                                        }
                                    }
                                }
                                runningJobs[node] = job
                            }
                        }
                    }
                }
            }
        }

        checkAndSpawnReady()

        // Wait for all spawned jobs to finish
        while (true) {
            val jobs = runningJobs.values
            if (jobs.isNotEmpty() && jobs.all { !it.isActive }) {
                break
            }
            delay(10)
        }

        val finalResult = failureResult ?: ExecutionResult.Success
        context.observer?.onEvent(ExecutionEvent.PipelineCompleted(finalResult))
        finalResult
    }

    // ponytail: read straight off the node after execute() — Property.set() mutates in
    // place, so values computed during execution are still there. Cancelled tasks never
    // reach a TaskCompleted event at all, so they report nothing.
    private fun outputsOf(node: Node): Map<String, String> =
        (node as? Task)?.collectOutputs() ?: emptyMap()

    private fun cancelAllJobs(jobs: Collection<Job>) {
        for (job in jobs) {
            if (job.isActive) {
                job.cancel()
            }
        }
    }
}
