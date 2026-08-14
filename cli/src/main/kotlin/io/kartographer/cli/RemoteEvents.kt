package io.kartographer.cli

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Node
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
    private class RemoteNode(name: String) : Node(name)

    /** Returns null for messages the renderers have no event for (prompts, errors, completion). */
    fun toExecutionEvent(message: RunServerMessage): ExecutionEvent? = when (message.messageCase) {
        RunServerMessage.MessageCase.PIPELINE_STARTED -> ExecutionEvent.PipelineStarted(
            message.pipelineStarted.totalTasks,
            buildGraph(message.pipelineStarted.graphList)
        )
        RunServerMessage.MessageCase.TASK_QUEUED ->
            ExecutionEvent.TaskQueued(RemoteNode(message.taskQueued.task))
        RunServerMessage.MessageCase.TASK_STARTED ->
            ExecutionEvent.TaskStarted(RemoteNode(message.taskStarted.task))
        RunServerMessage.MessageCase.TASK_OUTPUT -> ExecutionEvent.TaskOutput(
            RemoteNode(message.taskOutput.task),
            message.taskOutput.line,
            message.taskOutput.isError
        )
        RunServerMessage.MessageCase.TASK_COMPLETED -> ExecutionEvent.TaskCompleted(
            RemoteNode(message.taskCompleted.task),
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

    fun toResult(status: RunStatus, errorMessage: String, exitCode: Int): ExecutionResult = when (status) {
        RunStatus.RUN_SUCCESS -> ExecutionResult.Success
        RunStatus.RUN_UP_TO_DATE -> ExecutionResult.UpToDate
        else -> ExecutionResult.Failure(
            RuntimeException(errorMessage.ifBlank { "Task failed" }),
            exitCode.takeIf { it != 0 } ?: 1
        )
    }

    private fun buildGraph(nodes: List<ResolvedNode>): AdjacencyList<Node, Unit> {
        val graph = AdjacencyList<Node, Unit>()
        val indices = nodes.associate { it.name to graph.push(RemoteNode(it.name)) }
        for (node in nodes) {
            val from = indices[node.name] ?: continue
            for (dependency in node.dependenciesList) {
                indices[dependency]?.let { graph.connect(from, it, Unit) }
            }
        }
        return graph
    }
}
