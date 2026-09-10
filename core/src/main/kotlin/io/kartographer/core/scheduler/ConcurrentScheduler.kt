package io.kartographer.core.scheduler

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSetCodec
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Node
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.local.HostExecutionEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ConcurrentScheduler(private val allNodes: Map<TaskId, Node>) {
    private class TaskFailed(val result: ExecutionResult.Failure<*>) : RuntimeException(result.error)

    suspend fun execute(
        targetNode: Node,
        context: ExecutionContext,
        checkTopology: (Node) -> Boolean,
        materializeInputs: suspend (Task<*, *>, ExecutionContext) -> MaterializedInputs = { task, taskContext ->
            MaterializedInputs(task.buildInputsAny(taskContext))
        }
    ): ExecutionResult<*> = supervisorScope {
        val plan = ExecutionPlanBuilder(allNodes).build(targetNode)
        val graph = io.kartographer.core.model.graph.AdjacencyList<Node, Unit>()
        val indices = plan.nodes.associateWith { graph.push(it) }
        plan.dependencies.forEach { (node, deps) ->
            deps.forEach { dep -> graph.connect(indices.getValue(node), indices.getValue(dep), Unit) }
        }

        context.observer?.onEvent(ExecutionEvent.PipelineStarted(plan.nodes.size, graph))
        plan.nodes.forEach { context.observer?.onEvent(ExecutionEvent.TaskQueued(it)) }

        val jobs = ConcurrentHashMap<Node, Deferred<Unit>>()
        val ran = ConcurrentHashMap.newKeySet<Node>()
        val produced = ConcurrentHashMap<String, Map<String, Any?>>()

        fun schedule(node: Node): Deferred<Unit> = jobs.computeIfAbsent(node) {
            async {
                (plan.dependencies[node] ?: emptySet()).map(::schedule).awaitAll()
                context.observer?.onEvent(ExecutionEvent.TaskStarted(node))

                if (node !is InvocationNode) {
                    val result = ExecutionResult.Success(NoOutputs())
                    context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result))
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

                val taskContext = context.copy(
                    task = task,
                    executionNode = node,
                    invocationId = node.ref.id,
                    prompter = context.prompter.forTask(node)
                )
                val finalContext = taskContext.copy(
                    executionEnvironment = context.executionEnvironment ?: HostExecutionEnvironment(taskContext)
                )

                // Everything from here on resolves providers: inputs read them,
                // and so can `execute`. The scope carries the outputs produced so
                // far, so an Output provider can find its dependency's result.
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

                val dependencyRan = (plan.dependencies[node] ?: emptySet()).any { it in ran }
                if (withContext(resolution) { task.isUpToDate(invocationContext, dependencyRan) }) {
                    val outputs = task.buildOutputsAny()
                    TaskStateStore.restoreOutputs(invocationContext, task, outputs)
                    val result = ExecutionResult.UpToDate(outputs)
                    withContext(resolution) {
                        produced[node.ref.id] = outputs.producedValues()
                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, outputs.rendered()))
                    }
                    return@async
                }

                val result = try {
                    withContext(resolution) {
                        task.executeAny(invocationContext, prepared.inputSet)
                    }.also { returned ->
                        require(returned.outputs.schema() == task.buildOutputsAny().schema()) {
                            "Task '${task.name}' returned an OutputSet with a different schema."
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    ExecutionResult.Failure(error, 1, task.buildOutputsAny())
                }
                produced[node.ref.id] = withContext(resolution) { result.outputs.producedValues() }
                val rendered = withContext(resolution) { result.outputs.rendered() }

                when (result) {
                    is ExecutionResult.Success -> {
                        ran.add(node)
                        if (!task.alwaysRuns) withContext(resolution) {
                            TaskStateStore.saveState(invocationContext, task, result.outputs)
                        }
                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, rendered))
                    }
                    is ExecutionResult.UpToDate ->
                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, rendered))
                    is ExecutionResult.Failure -> {
                        context.observer?.onEvent(ExecutionEvent.TaskCompleted(node, result, rendered))
                        throw TaskFailed(result)
                    }
                }
            }
        }

        val finalResult: ExecutionResult<*> = try {
            schedule(plan.target).await()
            ExecutionResult.Success(NoOutputs())
        } catch (failure: TaskFailed) {
            jobs.values.forEach { it.cancel() }
            failure.result
        }
        context.observer?.onEvent(ExecutionEvent.PipelineCompleted(finalResult))
        finalResult
    }
}

data class MaterializedInputs(
    val inputSet: InputSet,
    val cliFields: Map<String, String> = emptyMap(),
    val beforeConfiguration: Map<String, Any?> = InputSetCodec.fields(inputSet)
)

private data class PreparedInvocation(val inputSet: InputSet, val inputHash: String)
