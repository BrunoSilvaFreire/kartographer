package io.kartographer.core.scheduler

import io.kartographer.core.model.DependencyCondition
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionObserver
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.InputSetCodec
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Node
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.ReadinessCondition
import io.kartographer.core.model.SECRET_MASK
import io.kartographer.core.model.SemaphoreHandle
import io.kartographer.core.model.SemaphoreRegistry
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.core.model.TaskLifecycle
import io.kartographer.local.HostExecutionEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class ConcurrentScheduler(private val allNodes: Map<TaskId, Node>) {
    private class TaskFailed(val result: ExecutionResult.Failure<*>) : RuntimeException(result.error)
    private data class EmittedOutput(val value: Any?, val secret: Boolean)

    private fun jobLimit(context: ExecutionContext): Int =
        context.environment["KARTO_JOBS"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    private fun failsFast(context: ExecutionContext): Boolean =
        context.environment["KARTO_FAIL_FAST"]?.lowercase() in setOf("1", "true", "yes")

    suspend fun execute(
        targetNode: Node,
        context: ExecutionContext,
        checkTopology: (Node) -> Boolean,
        materializeInputs: suspend (Task<*, *>, ExecutionContext) -> MaterializedInputs = { task, taskContext ->
            MaterializedInputs(task.buildInputsAny(taskContext))
        }
    ): ExecutionResult<*> = supervisorScope {
        val plan = ExecutionPlanBuilder(allNodes).build(targetNode)
        val graph = io.kartographer.core.model.graph.AdjacencyList<Node, DependencyCondition>()
        val indices = plan.nodes.associateWith { graph.push(it) }
        plan.dependencies.forEach { (node, deps) ->
            deps.forEach { dep -> graph.connect(indices.getValue(node), indices.getValue(dep.node), dep.condition) }
        }

        context.observer?.onEvent(ExecutionEvent.PipelineStarted(plan.nodes.size, graph))
        plan.nodes.forEach { context.observer?.onEvent(ExecutionEvent.TaskQueued(it)) }

        val jobs = ConcurrentHashMap<Node, Deferred<Unit>>()
        val ready = ConcurrentHashMap<Node, CompletableDeferred<Unit>>()
        val ran = ConcurrentHashMap.newKeySet<Node>()
        val produced = ConcurrentHashMap<String, Map<String, Any?>>()
        val expectedStops = ConcurrentHashMap.newKeySet<Node>()
        val fatalServiceFailure = CompletableDeferred<TaskFailed>()

        val dependencyClosures = ConcurrentHashMap<Node, Map<String, Task<*, *>>>()
        fun closureOf(node: Node): Map<String, Task<*, *>> = dependencyClosures.computeIfAbsent(node) {
            val found = linkedMapOf<String, Task<*, *>>()
            val seen = mutableSetOf<Node>()
            val queue = ArrayDeque(plan.dependencies[node].orEmpty().map { it.node })
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!seen.add(next)) continue
                if (next is InvocationNode) found[next.ref.id] = next.task
                queue.addAll(plan.dependencies[next].orEmpty().map { it.node })
            }
            found
        }

        val declaredSemaphores: List<SemaphoreHandle> = plan.nodes
            .filterIsInstance<InvocationNode>()
            .flatMap { it.task.semaphores }
        val semaphores = SemaphoreRegistry(declaredSemaphores)
        val jobSlots = Semaphore(jobLimit(context))

        fun isService(node: Node): Boolean =
            node is InvocationNode && node.task.lifecycle == TaskLifecycle.SERVICE

        fun rendered(outputs: Map<String, EmittedOutput>): Map<String, String> =
            outputs.mapValues { (_, output) -> if (output.secret) SECRET_MASK else output.value.toString() }

        fun serviceRoots(node: Node): List<InvocationNode> = when (node) {
            is InvocationNode -> listOf(node)
            else -> plan.dependencies[node].orEmpty().flatMap { serviceRoots(it.node) }
        }

        val roots = serviceRoots(plan.target)
        val persistentRun = roots.isNotEmpty() && roots.all { it.task.lifecycle == TaskLifecycle.SERVICE }
        val expectedServiceCompletions = plan.dependencies.values.flatten()
            .filter { it.condition == DependencyCondition.COMPLETED && isService(it.node) }
            .mapTo(mutableSetOf()) { it.node }

        fun serviceStopOrder(): List<Node> {
            val visited = mutableSetOf<Node>()
            val ordered = mutableListOf<Node>()
            fun visit(node: Node) {
                if (!visited.add(node)) return
                plan.dependencies[node].orEmpty().forEach { visit(it.node) }
                if (isService(node)) ordered.add(node)
            }
            visit(plan.target)
            return ordered.asReversed()
        }

        suspend fun stopServices() {
            for (node in serviceStopOrder()) {
                val job = jobs[node] ?: continue
                if (!job.isCompleted) {
                    expectedStops.add(node)
                    job.cancelAndJoin()
                }
            }
        }

        lateinit var schedule: (Node) -> Deferred<Unit>
        schedule = { node ->
            jobs.computeIfAbsent(node) {
                val readyGate = ready.computeIfAbsent(node) { CompletableDeferred() }
                async {
                    try {
                        val dependencies = plan.dependencies[node].orEmpty()
                        val dependencyJobs = dependencies.associateWith { schedule(it.node) }
                        suspend fun awaitDependencies() {
                            for ((dependency, job) in dependencyJobs) {
                                try {
                                    if (dependency.condition == DependencyCondition.READY) {
                                        ready.getValue(dependency.node).await()
                                    } else {
                                        job.await()
                                    }
                                } catch (failure: TaskFailed) {
                                    if (!(node is InvocationNode && node.task.runsAfterDependencyFailure && !failsFast(context))) {
                                        throw failure
                                    }
                                }
                            }
                        }
                        awaitDependencies()
                        context.observer?.onEvent(ExecutionEvent.TaskStarted(node))

                        if (node !is InvocationNode) {
                            readyGate.complete(Unit)
                            context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, ExecutionResult.Success(NoOutputs())))
                            return@async
                        }

                        val task = node.task
                        if (!checkTopology(task)) {
                            val result = ExecutionResult.Failure(
                                RuntimeException("Node '${node.name}' topology requirements not satisfied."),
                                1,
                                task.buildOutputsAny()
                            )
                            context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result))
                            throw TaskFailed(result)
                        }

                        val liveOutputs = ConcurrentHashMap<String, EmittedOutput>()
                        val readiness = task.serviceSpec?.readiness
                        val readinessReached = if (task.lifecycle == TaskLifecycle.SERVICE) {
                            CompletableDeferred<Unit>()
                        } else readyGate
                        val delegateObserver = context.observer
                        val taskObserver = if (readiness is ReadinessCondition.Log) {
                            object : ExecutionObserver {
                                override fun onEvent(event: ExecutionEvent) {
                                    if (event is ExecutionEvent.TaskOutput && readiness.pattern.containsMatchIn(event.line)) {
                                        readinessReached.complete(Unit)
                                    }
                                    delegateObserver?.onEvent(event)
                                }
                            }
                        } else delegateObserver

                        val taskContext = context.copy(
                            task = task,
                            executionNode = node,
                            invocationId = node.ref.id,
                            observer = taskObserver,
                            prompter = context.prompter.forTask(node),
                            dependencyTasks = closureOf(node),
                            serviceReadySignal = if (readiness == ReadinessCondition.Signal) ({ readinessReached.complete(Unit) }) else null,
                            emittedOutputConsumer = { name, value, secret ->
                                liveOutputs[name] = EmittedOutput(value, secret)
                            }
                        )
                        val finalContext = taskContext.copy(
                            executionEnvironment = context.executionEnvironment ?: HostExecutionEnvironment(taskContext)
                        )
                        val resolution = ProviderResolution(finalContext, produced.toMap())

                        val prepared = try {
                            val materialized = task.inputBuildMutex.withLock {
                                withContext(resolution) { materializeInputs(task, finalContext) }
                            }
                            val inputSet = materialized.inputSet
                            node.configuredDependency?.configureAny(inputSet)
                            val configuredFields = InputSetCodec.fields(inputSet)
                            materialized.cliFields.forEach { (field, cliName) ->
                                require(configuredFields[field] == materialized.beforeConfiguration[field]) {
                                    "Configured invocation '${node.name}' conflicts with CLI value for --$cliName."
                                }
                            }
                            PreparedInvocation(inputSet, InputSetCodec.hash(inputSet))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            val result = ExecutionResult.Failure(error, 1, task.buildOutputsAny())
                            context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result))
                            throw TaskFailed(result)
                        }
                        val invocationContext = finalContext.copy(inputHash = prepared.inputHash)

                        val dependencyRan = dependencies.any { it.node in ran }
                        if (withContext(resolution) { task.isUpToDate(invocationContext, dependencyRan) }) {
                            val outputs = task.buildOutputsAny()
                            TaskStateStore.restoreOutputs(invocationContext, task, outputs)
                            val result = ExecutionResult.UpToDate(outputs)
                            withContext(resolution) {
                                produced[node.ref.id] = outputs.producedValues()
                                readyGate.complete(Unit)
                                context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputs.rendered()))
                            }
                            return@async
                        }

                        var slotHeld = false
                        var leases: io.kartographer.core.model.SemaphoreLeases? = null
                        try {
                            jobSlots.acquire()
                            slotHeld = true
                            val wanted = task.semaphoresToAcquireAny(prepared.inputSet)
                            leases = semaphores.acquire(wanted) { waitingFor ->
                                context.observer?.onEvent(
                                    ExecutionEvent.TaskOutput(node, "Waiting for semaphore '$waitingFor'...", false)
                                )
                            }
                            val runContext = invocationContext.copy(semaphores = leases)

                            if (task.lifecycle == TaskLifecycle.SERVICE) {
                                val execution = async {
                                    withContext(resolution) { task.executeAny(runContext, prepared.inputSet) }
                                }
                                val probe = when (val condition = readiness!!) {
                                    is ReadinessCondition.Tcp, is ReadinessCondition.Http -> async {
                                        try {
                                            awaitNetworkReadiness(condition)
                                            readinessReached.complete(Unit)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Throwable) {
                                            readinessReached.completeExceptionally(error)
                                        }
                                    }
                                    else -> null
                                }
                                try {
                                    val timeout = task.serviceSpec!!.timeout
                                    suspend fun awaitReadyOrExit() {
                                        select<Unit> {
                                            readinessReached.onAwait { }
                                            execution.onAwait { returned ->
                                                val failure = when (returned) {
                                                    is ExecutionResult.Failure -> returned
                                                    else -> ExecutionResult.Failure(
                                                        IllegalStateException("Service '${node.name}' exited before becoming ready."),
                                                        1,
                                                        returned.outputs
                                                    )
                                                }
                                                context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, failure))
                                                throw TaskFailed(failure)
                                            }
                                        }
                                    }
                                    if (timeout == Duration.INFINITE) awaitReadyOrExit()
                                    else withTimeout(timeout.inWholeMilliseconds) { awaitReadyOrExit() }
                                } catch (timeout: TimeoutCancellationException) {
                                    execution.cancelAndJoin()
                                    throw IllegalStateException(
                                        "Service '${node.name}' did not become ready within ${task.serviceSpec!!.timeout}."
                                    )
                                } catch (cancelled: CancellationException) {
                                    execution.cancelAndJoin()
                                    throw cancelled
                                } catch (error: Throwable) {
                                    execution.cancelAndJoin()
                                    throw error
                                } finally {
                                    probe?.cancel()
                                }

                                val snapshot = liveOutputs.mapValues { it.value.value }
                                produced[node.ref.id] = snapshot
                                context.observer?.onEvent(ExecutionEvent.TaskReady(node, rendered(liveOutputs)))
                                readyGate.complete(Unit)
                                if (slotHeld) {
                                    jobSlots.release()
                                    slotHeld = false
                                }

                                val returned = execution.await()
                                if (node in expectedServiceCompletions && returned !is ExecutionResult.Failure) {
                                    val finalValues = withContext(resolution) { returned.outputs.producedValues() }
                                    produced[node.ref.id] = snapshot + finalValues
                                    context.observer?.onEvent(
                                        ExecutionEvent.TaskCompleted(node, returned, rendered(liveOutputs))
                                    )
                                    return@async
                                }
                                val result = when (returned) {
                                    is ExecutionResult.Failure -> returned
                                    else -> ExecutionResult.Failure(
                                        IllegalStateException("Service '${node.name}' exited after becoming ready."),
                                        1,
                                        returned.outputs
                                    )
                                }
                                context.observer?.onEvent(
                                    ExecutionEvent.TaskCompleted(node, result, rendered(liveOutputs))
                                )
                                throw TaskFailed(result)
                            }

                            val result = withContext(resolution) {
                                task.executeAny(runContext, prepared.inputSet)
                            }.also { returned ->
                                require(returned.outputs.schema() == task.buildOutputsAny().schema()) {
                                    "Task '${task.name}' returned an OutputSet with a different schema."
                                }
                            }
                            produced[node.ref.id] = withContext(resolution) { result.outputs.producedValues() }
                            val resultRendered = withContext(resolution) { result.outputs.rendered() }
                            readyGate.complete(Unit)
                            when (result) {
                                is ExecutionResult.Success -> {
                                    ran.add(node)
                                    if (!task.alwaysRuns) withContext(resolution) {
                                        TaskStateStore.saveState(invocationContext, task, result.outputs)
                                    }
                                    context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, resultRendered))
                                }
                                is ExecutionResult.UpToDate ->
                                    context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, resultRendered))
                                is ExecutionResult.Failure -> {
                                    context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, resultRendered))
                                    throw TaskFailed(result)
                                }
                            }
                        } finally {
                            if (slotHeld) jobSlots.release()
                            leases?.releaseAll()
                        }
                    } catch (cancelled: CancellationException) {
                        if (isService(node)) {
                            withContext(NonCancellable) {
                                context.observer?.onEvent(ExecutionEvent.TaskStopped(node))
                            }
                        }
                        throw cancelled
                    } catch (failure: TaskFailed) {
                        readyGate.completeExceptionally(failure)
                        if (isService(node) && node !in expectedStops) fatalServiceFailure.complete(failure)
                        throw failure
                    } catch (error: Throwable) {
                        val outputs = (node as? InvocationNode)?.task?.buildOutputsAny() ?: NoOutputs()
                        val failure = TaskFailed(ExecutionResult.Failure(error, 1, outputs))
                        readyGate.completeExceptionally(failure)
                        if (isService(node) && node !in expectedStops) fatalServiceFailure.complete(failure)
                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, failure.result))
                        throw failure
                    }
                }
            }
        }

        val targetJob = schedule(plan.target)
        var finalResult: ExecutionResult<*>? = null
        try {
            if (persistentRun) {
                if (isService(plan.target)) ready.getValue(plan.target).await() else targetJob.await()
                throw fatalServiceFailure.await()
            } else {
                select<Unit> {
                    targetJob.onAwait { }
                    fatalServiceFailure.onAwait { throw it }
                }
                finalResult = ExecutionResult.Success(NoOutputs())
            }
        } catch (failure: TaskFailed) {
            if (fatalServiceFailure.isCompleted || failsFast(context)) {
                jobs.values.forEach { it.cancel() }
            } else {
                while (true) {
                    val pending = jobs.entries
                        .filter { !isService(it.key) && !it.value.isCompleted }
                        .map { it.value }
                    if (pending.isEmpty()) break
                    pending.forEach { runCatching { it.await() } }
                }
            }
            finalResult = failure.result
        } finally {
            withContext(NonCancellable) { stopServices() }
        }

        val result = finalResult ?: ExecutionResult.Success(NoOutputs())
        context.observer?.onEvent(ExecutionEvent.PipelineCompleted(result))
        result
    }
}

data class MaterializedInputs(
    val inputSet: InputSet,
    val cliFields: Map<String, String> = emptyMap(),
    val beforeConfiguration: Map<String, Any?> = InputSetCodec.fields(inputSet)
)

private data class PreparedInvocation(val inputSet: InputSet, val inputHash: String)
