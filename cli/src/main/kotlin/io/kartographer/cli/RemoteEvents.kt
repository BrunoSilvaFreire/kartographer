package io.kartographer.cli

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Node
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.DependencyCondition
import io.kartographer.core.model.TaskLifecycle
import io.kartographer.core.model.graph.AdjacencyList
import io.kartographer.daemon.proto.ResolvedNode
import io.kartographer.daemon.proto.RunServerMessage
import io.kartographer.daemon.proto.RunStatus

/**
 * Translates the daemon's stream back into the [ExecutionEvent] model the renderers expect.
 *
 * Keeping the renderers on the local event type means `PlainRenderer` and `MosaicRenderer`
 * did not have to change when execution moved off the CLI — they still see a pipeline
 * running, they just no longer own it.
 */
object RemoteEvents {

    /**
     * A node that exists only to label a rendered event. The CLI has no pipeline loaded and
     * no task classes on its classpath, so the name is all there is — and all the renderers
     * ever read.
     */
    private class RemoteNode(
        name: String,
        override val executionId: String = name,
        override val lifecycle: TaskLifecycle = TaskLifecycle.FINITE
    ) : Node(name)

    /** Returns null for messages the renderers have no event for (prompts, errors, completion). */
    fun toExecutionEvent(message: RunServerMessage): ExecutionEvent? = when (message.messageCase) {
        RunServerMessage.MessageCase.PIPELINE_STARTED -> ExecutionEvent.PipelineStarted(
            message.pipelineStarted.totalTasks,
            buildGraph(message.pipelineStarted.graphList)
        )
        RunServerMessage.MessageCase.TASK_QUEUED ->
            ExecutionEvent.TaskQueued(RemoteNode(message.taskQueued.task, message.taskQueued.invocationId))
        RunServerMessage.MessageCase.TASK_STARTED ->
            ExecutionEvent.TaskStarted(RemoteNode(message.taskStarted.task, message.taskStarted.invocationId))
        RunServerMessage.MessageCase.TASK_READY -> ExecutionEvent.TaskReady(
            RemoteNode(message.taskReady.task, message.taskReady.invocationId, TaskLifecycle.SERVICE),
            message.taskReady.outputsMap
        )
        RunServerMessage.MessageCase.TASK_STOPPED -> ExecutionEvent.TaskStopped(
            RemoteNode(message.taskStopped.task, message.taskStopped.invocationId, TaskLifecycle.SERVICE),
            message.taskStopped.outputsMap
        )
        RunServerMessage.MessageCase.TASK_OUTPUT -> ExecutionEvent.TaskOutput(
            RemoteNode(message.taskOutput.task, message.taskOutput.invocationId),
            message.taskOutput.line,
            message.taskOutput.isError
        )
        RunServerMessage.MessageCase.TASK_OUTPUT_PRODUCED -> ExecutionEvent.TaskOutputProduced(
            RemoteNode(message.taskOutputProduced.task, message.taskOutputProduced.invocationId),
            message.taskOutputProduced.name,
            message.taskOutputProduced.value,
            message.taskOutputProduced.isSecret
        )
        RunServerMessage.MessageCase.TASK_COMPLETED -> ExecutionEvent.TaskCompleted(
            RemoteNode(message.taskCompleted.task, message.taskCompleted.invocationId),
            toResult(message.taskCompleted.status, message.taskCompleted.errorMessage, message.taskCompleted.exitCode),
            message.taskCompleted.outputsMap
        )
        RunServerMessage.MessageCase.PIPELINE_COMPLETED -> ExecutionEvent.PipelineCompleted(
            toResult(
                message.pipelineCompleted.status,
                message.pipelineCompleted.errorMessage,
                message.pipelineCompleted.exitCode
            )
        )
        else -> null
    }

    fun toResult(status: RunStatus, errorMessage: String, exitCode: Int): ExecutionResult<NoOutputs> = when (status) {
        RunStatus.RUN_SUCCESS -> ExecutionResult.Success(NoOutputs())
        RunStatus.RUN_UP_TO_DATE -> ExecutionResult.UpToDate(NoOutputs())
        RunStatus.RUN_CANCELLED -> ExecutionResult.Failure(
            RuntimeException(errorMessage.ifBlank { "Run cancelled" }),
            exitCode.takeIf { it != 0 } ?: 130,
            NoOutputs()
        )
        else -> ExecutionResult.Failure(
            RuntimeException(errorMessage.ifBlank { "Task failed" }),
            exitCode.takeIf { it != 0 } ?: 1,
            NoOutputs()
        )
    }

    private fun buildGraph(nodes: List<ResolvedNode>): AdjacencyList<Node, DependencyCondition> {
        val graph = AdjacencyList<Node, DependencyCondition>()
        val indices = nodes.associate { node ->
            node.invocationId to graph.push(
                RemoteNode(
                    node.name,
                    node.invocationId,
                    if (node.lifecycle == io.kartographer.daemon.proto.TaskLifecycle.TASK_SERVICE)
                        TaskLifecycle.SERVICE else TaskLifecycle.FINITE
                )
            )
        }
        for (node in nodes) {
            val from = indices[node.invocationId] ?: continue
            for (dependency in node.dependenciesList) {
                indices[dependency.invocationId]?.let {
                    graph.connect(
                        from,
                        it,
                        if (dependency.condition == io.kartographer.daemon.proto.DependencyCondition.DEPENDENCY_READY)
                            DependencyCondition.READY else DependencyCondition.COMPLETED
                    )
                }
            }
        }
        return graph
    }
}
