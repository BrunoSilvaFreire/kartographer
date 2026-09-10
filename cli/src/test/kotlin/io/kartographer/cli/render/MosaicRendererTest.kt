package io.kartographer.cli.render

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Node
import io.kartographer.core.model.graph.AdjacencyList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class TestNode(name: String) : Node(name)

class MosaicRendererTest {

    private fun node(name: String) = TestNode(name)

    private fun graph(vararg deps: Pair<String, String>): AdjacencyList<Node, Unit> {
        val g = AdjacencyList<Node, Unit>()
        val indices = mutableMapOf<String, Int>()
        fun indexOf(name: String): Int = indices.getOrPut(name) { g.push(node(name)) }
        for ((from, to) in deps) {
            g.connect(indexOf(from), indexOf(to), Unit)
        }
        return g
    }

    @Test
    fun `pipeline started computes dependency-first navigation order`() {
        val state = reduce(PipelineViewState(), ExecutionEvent.PipelineStarted(3, graph("c" to "b", "b" to "a")))

        assertEquals(3, state.totalTasks)
        assertEquals(listOf("a", "b", "c"), state.navOrder)
        assertEquals(3, state.graph?.size)
    }

    @Test
    fun `task queued then started updates status`() {
        var state = reduce(PipelineViewState(), ExecutionEvent.TaskQueued(node("t")))
        assertEquals(TaskStatus.QUEUED, state.tasks["t"]?.status)

        state = reduce(state, ExecutionEvent.TaskStarted(node("t")))
        assertEquals(TaskStatus.RUNNING, state.tasks["t"]?.status)
    }

    @Test
    fun `task output appends to per-task logs`() {
        var state = reduce(PipelineViewState(), ExecutionEvent.TaskQueued(node("t")))
        state = reduce(state, ExecutionEvent.TaskOutput(node("t"), "hello", isError = false))
        state = reduce(state, ExecutionEvent.TaskOutput(node("t"), "world", isError = false))

        assertEquals(listOf("hello", "world"), state.tasks["t"]?.logs?.toList())
    }

    @Test
    fun `task output caps logs at 100 lines`() {
        var state = reduce(PipelineViewState(), ExecutionEvent.TaskQueued(node("t")))
        for (i in 0 until 105) {
            state = reduce(state, ExecutionEvent.TaskOutput(node("t"), "line-$i", isError = false))
        }

        assertEquals(100, state.tasks["t"]?.logs?.size)
        assertEquals("line-104", state.tasks["t"]?.logs?.last())
    }

    @Test
    fun `task completion success increments count`() {
        var state = reduce(PipelineViewState(), ExecutionEvent.TaskQueued(node("t")))
        state = reduce(
            state,
            ExecutionEvent.TaskCompleted(node("t"), ExecutionResult.Success(NoOutputs()), emptyMap())
        )

        assertEquals(TaskStatus.SUCCESS, state.tasks["t"]?.status)
        assertEquals(1, state.completedCount)
    }

    @Test
    fun `task completion failure records error line`() {
        var state = reduce(PipelineViewState(), ExecutionEvent.TaskQueued(node("t")))
        state = reduce(
            state,
            ExecutionEvent.TaskCompleted(
                node("t"),
                ExecutionResult.Failure(RuntimeException("boom"), 1, NoOutputs()),
                emptyMap()
            )
        )

        assertEquals(TaskStatus.FAILED, state.tasks["t"]?.status)
        assertEquals("ERROR: boom", state.tasks["t"]?.logs?.last())
    }

    @Test
    fun `pipeline completed marks finished`() {
        val state = reduce(
            PipelineViewState(),
            ExecutionEvent.PipelineCompleted(ExecutionResult.Success(NoOutputs()))
        )

        assertTrue(state.isFinished)
        assertTrue(state.finalResult is ExecutionResult.Success<*>)
    }
}
