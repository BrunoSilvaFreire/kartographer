package io.kartographer.cli.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Text
import io.kartographer.cli.render.graph.LayeredLayout
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Node
import io.kartographer.core.model.DependencyCondition
import io.kartographer.core.model.graph.AdjacencyList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_LOG_LINES = 100
private const val DETAIL_LOG_LINES = 20

enum class TaskStatus {
    QUEUED, RUNNING, READY, STOPPED, SUCCESS, UP_TO_DATE, FAILED
}

private fun statusLabel(status: TaskStatus) = when (status) {
    TaskStatus.SUCCESS -> "ok"
    TaskStatus.UP_TO_DATE -> "up-to-date"
    TaskStatus.FAILED -> "FAILED"
    else -> status.name.lowercase()
}

private fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.RUNNING -> Color.Yellow
    TaskStatus.READY, TaskStatus.SUCCESS, TaskStatus.UP_TO_DATE -> Color.Green
    TaskStatus.STOPPED -> Color(120, 120, 120)
    TaskStatus.FAILED -> Color.Red
    TaskStatus.QUEUED -> Color(120, 120, 120)
}

private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

private object SpinnerTick

private fun statusGlyph(status: TaskStatus, spinnerFrame: Int): String = when (status) {
    TaskStatus.RUNNING -> SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.size]
    TaskStatus.READY -> "●"
    TaskStatus.STOPPED -> "■"
    TaskStatus.SUCCESS -> "✓"
    TaskStatus.UP_TO_DATE -> "="
    TaskStatus.FAILED -> "✗"
    TaskStatus.QUEUED -> "·"
}

data class TaskViewState(
    val name: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val logs: ArrayDeque<String> = ArrayDeque(MAX_LOG_LINES),
    val outputs: Map<String, String> = emptyMap()
)

data class PipelineViewState(
    val totalTasks: Int = 0,
    val completedCount: Int = 0,
    val tasks: Map<String, TaskViewState> = emptyMap(),
    val isFinished: Boolean = false,
    val finalResult: ExecutionResult<*>? = null,
    val graph: AdjacencyList<Node, DependencyCondition>? = null,
    val navOrder: List<String> = emptyList()
)

/**
 * Pure reducer: folds one [ExecutionEvent] into the next [PipelineViewState].
 *
 * Keeping this free of any Compose/UI concerns makes it straightforward to unit-test the
 * state machine (status transitions, log capping, completion bookkeeping). Layout is a UI
 * concern and is computed by the graph pane from [graph].
 */
internal fun reduce(state: PipelineViewState, event: ExecutionEvent): PipelineViewState = when (event) {
    is ExecutionEvent.PipelineStarted -> state.copy(
        totalTasks = event.totalTasks,
        graph = event.graph,
        navOrder = topoOrder(event.graph)
    )

    is ExecutionEvent.TaskQueued -> {
        val tasks = state.tasks.toMutableMap()
        tasks[event.task.name] = TaskViewState(event.task.name, TaskStatus.QUEUED)
        state.copy(tasks = tasks)
    }

    is ExecutionEvent.TaskStarted -> {
        val tasks = state.tasks.toMutableMap()
        val tvs = tasks[event.task.name] ?: TaskViewState(event.task.name)
        tasks[event.task.name] = tvs.copy(status = TaskStatus.RUNNING)
        state.copy(tasks = tasks)
    }

    is ExecutionEvent.TaskReady -> {
        val tasks = state.tasks.toMutableMap()
        val tvs = tasks[event.task.name] ?: TaskViewState(event.task.name)
        tasks[event.task.name] = tvs.copy(status = TaskStatus.READY, outputs = event.outputs)
        state.copy(tasks = tasks)
    }

    is ExecutionEvent.TaskStopped -> {
        val tasks = state.tasks.toMutableMap()
        val tvs = tasks[event.task.name] ?: TaskViewState(event.task.name)
        tasks[event.task.name] = tvs.copy(status = TaskStatus.STOPPED, outputs = event.outputs)
        state.copy(tasks = tasks, completedCount = state.completedCount + 1)
    }

    is ExecutionEvent.TaskOutput -> {
        val tasks = state.tasks.toMutableMap()
        val tvs = tasks[event.task.name] ?: TaskViewState(event.task.name)
        if (tvs.logs.size >= MAX_LOG_LINES) tvs.logs.removeFirst()
        tvs.logs.addLast(event.line)
        tasks[event.task.name] = tvs
        state.copy(tasks = tasks)
    }

    is ExecutionEvent.TaskOutputProduced -> {
        val tasks = state.tasks.toMutableMap()
        val tvs = tasks[event.task.name] ?: TaskViewState(event.task.name)
        val outputs = tvs.outputs.toMutableMap()
        outputs[event.name] = if (event.secret) io.kartographer.core.model.SECRET_MASK else event.value
        tasks[event.task.name] = tvs.copy(outputs = outputs)
        state.copy(tasks = tasks)
    }

    is ExecutionEvent.TaskCompleted -> {
        val tasks = state.tasks.toMutableMap()
        val tvs = tasks[event.task.name] ?: TaskViewState(event.task.name)
        val status = when (val res = event.result) {
            is ExecutionResult.Success -> TaskStatus.SUCCESS
            is ExecutionResult.UpToDate -> TaskStatus.UP_TO_DATE
            is ExecutionResult.Failure -> {
                if (tvs.logs.size >= MAX_LOG_LINES) tvs.logs.removeFirst()
                tvs.logs.addLast("ERROR: ${res.error.message}")
                TaskStatus.FAILED
            }
        }
        tasks[event.task.name] = tvs.copy(status = status, outputs = event.outputs)
        state.copy(tasks = tasks, completedCount = state.completedCount + 1)
    }

    is ExecutionEvent.PipelineCompleted ->
        state.copy(isFinished = true, finalResult = event.result)

    else -> state
}

/** Dependency-first ordering of node names, so navigation walks upstream tasks first. */
private fun topoOrder(graph: AdjacencyList<Node, DependencyCondition>): List<String> {
    val visited = mutableSetOf<Int>()
    val order = mutableListOf<String>()

    fun visit(index: Int) {
        if (index in visited) return
        visited += index
        for (dependency in graph.edgesFrom(index).keys) visit(dependency)
        graph.vertex(index)?.let { order += it.name }
    }

    for (index in graph.allVerticesIndices()) visit(index)
    return order
}

/** Short, single-column label for a node in the graph; the full name lives in the detail pane. */
private fun nodeLabel(name: String, status: TaskStatus, selected: Boolean, spinnerFrame: Int, maxWidth: Int): String {
    val glyph = statusGlyph(status, spinnerFrame)
    val short = name.substringAfterLast('/').take(maxWidth - 2)
    val marker = if (selected) ">" else " "
    return "$marker$glyph$short"
}

class MosaicRenderer : Renderer, PromptRenderer {
    override val events = Channel<ExecutionEvent>(Channel.UNLIMITED)
    override val promptAnswers = Channel<PromptAnswer>(Channel.UNLIMITED)
    private val promptRequests = Channel<PromptRequest>(Channel.UNLIMITED)

    override fun onPromptRequested(request: PromptRequest) {
        promptRequests.trySend(request)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun render() {
        runMosaic {
            var state by remember { mutableStateOf(PipelineViewState()) }
            var selected by remember { mutableStateOf<String?>(null) }
            var prompt by remember { mutableStateOf<PromptRequest?>(null) }
            var promptBuffer by remember { mutableStateOf("") }
            var spinnerFrame by remember { mutableStateOf(0) }

            // Single effect draining execution events, prompt requests, and a periodic tick that
            // advances the running-task spinner. Keeping it in one effect means the composition
            // finishes when `events` closes and `runMosaic` can return.
            LaunchedEffect(Unit) {
                while (true) {
                    val next = select<Any?> {
                        events.onReceiveCatching { it.getOrNull() }
                        promptRequests.onReceive { it }
                        onTimeout(80L) { SpinnerTick }
                    }
                    when (next) {
                        null -> break
                        SpinnerTick -> spinnerFrame++
                        is PromptRequest -> {
                            prompt = next
                            promptBuffer = ""
                        }
                        is ExecutionEvent -> {
                            state = reduce(state, next)
                            // Give the terminal a frame to paint the final summary before teardown.
                            if (next is ExecutionEvent.PipelineCompleted) delay(300)
                        }
                    }
                }
            }

            App(
                state = state,
                selected = selected,
                spinnerFrame = spinnerFrame,
                prompt = prompt,
                promptBuffer = promptBuffer,
                onKey = { key, ctrl ->
                    val activePrompt = prompt
                    if (activePrompt != null) {
                        when {
                            key == "Enter" -> {
                                promptAnswers.trySend(PromptAnswer(activePrompt.requestId, promptBuffer))
                                prompt = null
                                promptBuffer = ""
                                true
                            }
                            key == "Backspace" -> {
                                promptBuffer = promptBuffer.dropLast(1)
                                true
                            }
                            key.length == 1 && !ctrl -> {
                                promptBuffer += key
                                true
                            }
                            else -> false
                        }
                    } else {
                        when (key) {
                            "ArrowUp", "k" -> {
                                moveSelection(state.navOrder, selected, -1)?.let { selected = it }
                                true
                            }
                            "ArrowDown", "j" -> {
                                moveSelection(state.navOrder, selected, +1)?.let { selected = it }
                                true
                            }
                            "Escape" -> {
                                selected = null
                                true
                            }
                            else -> false
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun App(
        state: PipelineViewState,
        selected: String?,
        spinnerFrame: Int,
        prompt: PromptRequest?,
        promptBuffer: String,
        onKey: (String, Boolean) -> Boolean
    ) {
        Column(Modifier.onKeyEvent { event -> onKey(event.key, event.ctrl) }) {
            Text("Kartographer Pipeline Execution", color = Color.Blue)
            Text("Progress: ${state.completedCount} / ${state.totalTasks} tasks")
            Text("")

            if (prompt != null) {
                PromptPane(prompt, promptBuffer)
                Text("")
            }

            if (state.graph != null) {
                GraphPane(state, selected, spinnerFrame)
            } else {
                Text("Waiting for pipeline...")
            }
            Text("")
            Text("up/down or j/k select | Esc close panel", color = Color(128, 128, 128))

            if (selected != null) {
                Text("")
                DetailPane(selected, state.tasks[selected], spinnerFrame)
            }

            if (state.isFinished) {
                Text("")
                SummaryPane(state)
            }
        }
    }

    @Composable
    private fun GraphPane(state: PipelineViewState, selected: String?, spinnerFrame: Int) {
        val graph = state.graph ?: return
        val terminalSize = LocalTerminalState.current.size
        val availableWidth = max(40, terminalSize.columns - 2)
        // Reserve room above for the header/hint and below for the detail/summary panes, so the
        // graph plus the log pane never overflow the terminal.
        val availableHeight = max(8, terminalSize.rows - 30)

        val layout = remember(graph, availableWidth, availableHeight) {
            LayeredLayout(graph, maxWidth = availableWidth, maxHeight = availableHeight).apply { computeLayout() }
        }
        val edgesDim = layout.renderEdgesToStrings().joinToString("\n")

        val selectedVertex = selected?.let { name ->
            graph.allVerticesIndices().firstOrNull { graph.vertex(it)!!.name == name }
        }
        val edgesHighlight = layout.renderEdgesToStrings(setOfNotNull(selectedVertex)).joinToString("\n")

        Layout(content = {
            Text(edgesDim, color = Color(100, 100, 100))
            Text(edgesHighlight, color = Color.Yellow)
            for (v in graph.allVerticesIndices()) {
                val node = graph.vertex(v)!!
                val status = state.tasks[node.name]?.status ?: TaskStatus.QUEUED
                val isSelected = node.name == selected
                Text(
                    nodeLabel(node.name, status, isSelected, spinnerFrame, layout.nodeWidth),
                    color = if (isSelected) Color.Black else statusColor(status),
                    background = if (isSelected) Color.Yellow else Color.Unspecified
                )
            }
        }) { measurables, constraints ->
            val dimPlaceable = measurables[0].measure(constraints)
            val highlightPlaceable = measurables[1].measure(constraints)
            val nodePlaceables = measurables.drop(2).map { it.measure(constraints) }

            layout(layout.width, layout.height) {
                dimPlaceable.place(0, 0)
                highlightPlaceable.place(0, 0)

                var i = 0
                for (v in graph.allVerticesIndices()) {
                    val point = layout.positions[v]!!
                    val placeable = nodePlaceables[i++]
                    placeable.place(point.x.roundToInt(), point.y.roundToInt())
                }
            }
        }
    }

    @Composable
    private fun DetailPane(name: String, tvs: TaskViewState?, spinnerFrame: Int) {
        val status = tvs?.status ?: TaskStatus.QUEUED
        Text("-- $name --", color = statusColor(status), background = Color(30, 30, 30))
        Text("${statusGlyph(status, spinnerFrame)} ${statusLabel(status)}", color = statusColor(status))

        if (tvs != null && tvs.outputs.isNotEmpty()) {
            Text("")
            Text("Outputs:", color = Color(180, 180, 180))
            for ((key, value) in tvs.outputs) {
                Text("  $key = $value", color = Color(140, 140, 140))
            }
        }

        Text("")
        Text("Console:", color = Color(180, 180, 180))
        val logs = tvs?.logs.orEmpty()
        if (logs.isEmpty()) {
            Text("(no output yet)", color = Color(128, 128, 128))
        } else {
            for (line in logs.takeLast(DETAIL_LOG_LINES)) {
                Text(line)
            }
        }
    }

    @Composable
    private fun PromptPane(prompt: PromptRequest, buffer: String) {
        Column {
            if (prompt.elevatedTasks.isNotEmpty()) {
                Text("[SUDO] PRIVILEGE ESCALATION REQUIRED", color = Color.White, background = Color.Red)
                val shown = prompt.elevatedTasks.take(3)
                for (task in shown) {
                    Text("  - $task", color = Color.Yellow)
                }
                if (prompt.elevatedTasks.size > shown.size) {
                    Text("  ... and ${prompt.elevatedTasks.size - shown.size} more", color = Color.Yellow)
                }
            }
            Text(prompt.message, color = Color.White, background = Color(90, 90, 90))
            val display = if (prompt.isSecret) "*".repeat(buffer.length) else buffer
            Text("> $display ", color = Color.Black, background = Color.Yellow)
        }
    }

    @Composable
    private fun SummaryPane(state: PipelineViewState) {
        Text("Summary:")
        val completed = state.tasks.values.filter {
            it.status !in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.READY)
        }
        val width = completed.maxOfOrNull { it.name.length } ?: 0
        for (task in completed) {
            Text(" ${task.name.padEnd(width)}  ${statusLabel(task.status)}", color = statusColor(task.status))
            for ((key, value) in task.outputs) {
                Text("   ${" ".repeat(width)}  $key = $value", color = Color(128, 128, 128))
            }
        }
        Text("")
        when (state.finalResult) {
            is ExecutionResult.Success<*> -> Text("Pipeline finished successfully.", color = Color.Green)
            is ExecutionResult.UpToDate<*> -> Text("Pipeline finished (up-to-date).", color = Color.Green)
            is ExecutionResult.Failure<*> -> Text("Pipeline failed.", color = Color.Red)
            else -> {}
        }
    }
}

private fun moveSelection(order: List<String>, current: String?, delta: Int): String? {
    if (order.isEmpty()) return null
    val index = order.indexOf(current)
    val next = when {
        index < 0 -> if (delta > 0) 0 else order.lastIndex
        else -> (index + delta).coerceIn(0, order.lastIndex)
    }
    return order[next]
}
