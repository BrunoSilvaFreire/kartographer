package io.kartographer.core.model

import io.kartographer.core.model.graph.AdjacencyList

sealed interface ExecutionEvent {
    data class PipelineStarted(val totalTasks: Int, val graph: AdjacencyList<Node, Unit>) : ExecutionEvent
    data class TaskQueued(val task: Node) : ExecutionEvent
    data class TaskStarted(val task: Node) : ExecutionEvent
    data class TaskOutput(val task: Node, val line: String, val isError: Boolean) : ExecutionEvent
    data class TaskCompleted(val task: Node, val result: ExecutionResult) : ExecutionEvent
    data class PipelineCompleted(val result: ExecutionResult) : ExecutionEvent
    data class PromptRequested(val task: Node?, val message: String) : ExecutionEvent
    object PromptCompleted : ExecutionEvent
}

interface ExecutionObserver {
    fun onEvent(event: ExecutionEvent)
}
