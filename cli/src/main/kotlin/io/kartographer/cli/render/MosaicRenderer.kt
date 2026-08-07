package io.kartographer.cli.render

import androidx.compose.runtime.*
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Static
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.Layout
import kotlin.math.max
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.UserPrompter
import io.kartographer.cli.render.graph.ForceDirectedLayout
import kotlinx.coroutines.channels.Channel

enum class TaskStatus {
    QUEUED, RUNNING, SUCCESS, UP_TO_DATE, FAILED
}

private fun statusLabel(status: TaskStatus) = when (status) {
    TaskStatus.SUCCESS -> "ok"
    TaskStatus.UP_TO_DATE -> "up-to-date"
    TaskStatus.FAILED -> "FAILED"
    else -> status.name.lowercase()
}

data class TaskViewState(
    val name: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val logs: ArrayDeque<String> = ArrayDeque(100),
    val outputs: Map<String, String> = emptyMap()
)

data class PipelineViewState(
    val totalTasks: Int = 0,
    val completedCount: Int = 0,
    val tasks: Map<String, TaskViewState> = emptyMap(),
    val isFinished: Boolean = false,
    val finalResult: ExecutionResult? = null,
    val promptTask: Node? = null,
    val promptMessage: String? = null,
    val graphLines: List<String> = emptyList(),
    val graphLayout: ForceDirectedLayout? = null,
    val graph: io.kartographer.core.model.graph.AdjacencyList<Node, Unit>? = null
)

class MosaicRenderer : Renderer {
    override val events = Channel<ExecutionEvent>(Channel.UNLIMITED)

    override suspend fun render() = runMosaic {
        var viewState by mutableStateOf(PipelineViewState())
        var state = PipelineViewState()
        val globalLogs = mutableStateListOf<String>()

        setContent {
            RenderPipeline(viewState, globalLogs)
        }

        for (event in events) {
            try {
            when (event) {
                is ExecutionEvent.PipelineStarted -> {
                    val layout = ForceDirectedLayout(event.graph)
                    layout.computeLayout()
                    val lines = layout.renderEdgesToStrings()
                    state = state.copy(
                        totalTasks = event.totalTasks, 
                        graphLines = lines,
                        graphLayout = layout,
                        graph = event.graph
                    )
                }
                is ExecutionEvent.TaskQueued -> {
                    val newTasks = state.tasks.toMutableMap()
                    newTasks[event.task.name] = TaskViewState(event.task.name, TaskStatus.QUEUED)
                    state = state.copy(tasks = newTasks)
                }
                is ExecutionEvent.TaskStarted -> {
                    val newTasks = state.tasks.toMutableMap()
                    val tvs = newTasks[event.task.name] ?: TaskViewState(event.task.name)
                    newTasks[event.task.name] = tvs.copy(status = TaskStatus.RUNNING)
                    state = state.copy(tasks = newTasks)
                }
                is ExecutionEvent.TaskOutput -> {
                    val newTasks = state.tasks.toMutableMap()
                    val tvs = newTasks[event.task.name] ?: TaskViewState(event.task.name)
                    if (tvs.logs.size >= 100) tvs.logs.removeFirst()
                    tvs.logs.addLast(event.line)
                    newTasks[event.task.name] = tvs.copy(logs = tvs.logs)
                    
                    if (globalLogs.size >= 15) globalLogs.remove(globalLogs.first())
                    globalLogs.add("[${event.task.name}] ${event.line}")
                    
                    state = state.copy(tasks = newTasks)
                }
                is ExecutionEvent.TaskCompleted -> {
                    // Updates land on `state`, not `viewState`: the `viewState = state`
                    // below this `when` would otherwise discard them on the next event.
                    val newTasks = state.tasks.toMutableMap()
                    val tvs = newTasks[event.task.name] ?: TaskViewState(event.task.name)
                    val status = when (val res = event.result) {
                        is ExecutionResult.Success -> TaskStatus.SUCCESS
                        is ExecutionResult.UpToDate -> TaskStatus.UP_TO_DATE
                        is ExecutionResult.Failure -> {
                            val msg = "ERROR: ${res.error.message}"
                            if (tvs.logs.size >= 100) tvs.logs.removeFirst()
                            tvs.logs.addLast(msg)
                            globalLogs.add("[${event.task.name}] $msg")
                            TaskStatus.FAILED
                        }
                    }
                    newTasks[event.task.name] = tvs.copy(status = status, logs = tvs.logs, outputs = event.outputs)
                    state = state.copy(
                        tasks = newTasks,
                        completedCount = state.completedCount + 1
                    )
                }
                is ExecutionEvent.PipelineCompleted -> {
                    state = state.copy(isFinished = true, finalResult = event.result)
                    viewState = state
                    // A short pipeline can drain this channel inside a single frame, so
                    // returning immediately tears the UI down before the summary is ever
                    // painted. Give Mosaic a frame to draw the final state.
                    kotlinx.coroutines.delay(150)
                    break
                }
                is ExecutionEvent.PromptRequested -> {
                    state = state.copy(promptTask = event.task, promptMessage = event.message)
                }
                is ExecutionEvent.PromptCompleted -> {
                    state = state.copy(promptTask = null, promptMessage = null)
                }
            }
            viewState = state
            } catch (e: Exception) {
                globalLogs.add("ERROR in MosaicRenderer loop: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @Composable
    private fun RenderPipeline(viewState: PipelineViewState, globalLogs: androidx.compose.runtime.snapshots.SnapshotStateList<String>) {
        Column {
        Text("Kartographer Pipeline Execution", color = Color.Blue)
        Text("Progress: ${viewState.completedCount} / ${viewState.totalTasks} tasks")
        Text("")

        if (viewState.graphLayout != null && viewState.graph != null) {
            GraphComponent(viewState.graph, viewState.graphLayout, viewState.graphLines, viewState.tasks)
            Text("")
        }

        val runningTasks = viewState.tasks.values.filter { it.status == TaskStatus.RUNNING }
            val failedTasks = viewState.tasks.values.filter { it.status == TaskStatus.FAILED }
            
            if (runningTasks.isNotEmpty()) {
                Text("Running Tasks:")
                for (task in runningTasks) {
                    Text(" ⚙ ${task.name}", color = Color.Yellow)
                }
                Text("") // spacer
            }
            
            if (failedTasks.isNotEmpty()) {
                Text("Failed Tasks:")
                for (task in failedTasks) {
                    Text(" ❌ ${task.name}", color = Color.Red)
                    val errorMsg = viewState.tasks[task.name]?.logs?.lastOrNull() ?: "Unknown error"
                    Text("    Reason: $errorMsg", color = Color.Red)
                }
                Text("") // spacer
            }

            Static(globalLogs) { line ->
                Text(line)
            }

            if (viewState.isFinished) {
                val completed = viewState.tasks.values.filter { it.status != TaskStatus.QUEUED && it.status != TaskStatus.RUNNING }
                if (completed.isNotEmpty()) {
                    Text("") // spacer
                    Text("Summary:")
                    val width = completed.maxOf { it.name.length }
                    for (task in completed) {
                        val (glyph, color) = when (task.status) {
                            TaskStatus.SUCCESS -> "✔" to Color.Green
                            TaskStatus.UP_TO_DATE -> "≡" to Color.Green
                            else -> "✖" to Color.Red
                        }
                        Text(" $glyph ${task.name.padEnd(width)}  ${statusLabel(task.status)}", color = color)
                        for ((key, value) in task.outputs) {
                            Text("   ${" ".repeat(width)}  $key = $value", color = Color.BrightBlack)
                        }
                    }
                }
                Text("") // spacer
                when (viewState.finalResult) {
                    is ExecutionResult.Success -> Text("✅ Pipeline finished successfully.", color = Color.Green)
                    is ExecutionResult.UpToDate -> Text("✅ Pipeline finished (up-to-date).", color = Color.Green)
                    is ExecutionResult.Failure -> Text("❌ Pipeline failed.", color = Color.Red)
                    else -> {}
                }
            }

            if (viewState.promptMessage != null) {
                Text("")
                val taskName = viewState.promptTask?.name ?: "Unknown Task"
                Column {
                    Text(" 🔑 Sudo Password Required ", color = Color.White, background = Color.Red)
                    Text(" Task: $taskName ", color = Color.Black, background = Color.Yellow)
                    Text(" ${viewState.promptMessage} (Type password and press Enter) ", color = Color.White, background = Color.BrightBlack)
                }
            }
        }
    }
}

@Composable
fun GraphComponent(
    graph: io.kartographer.core.model.graph.AdjacencyList<Node, Unit>, 
    layoutParams: ForceDirectedLayout, 
    edgesLines: List<String>, 
    tasks: Map<String, TaskViewState>
) {
    Layout(content = {
        Text(edgesLines.joinToString("\n"), color = Color.BrightBlack)
        
        for (v in graph.allVerticesIndices()) {
            val task = graph.vertex(v)!!
            val state = tasks[task.name]
            val color = when (state?.status) {
                TaskStatus.RUNNING -> Color.Yellow
                TaskStatus.SUCCESS, TaskStatus.UP_TO_DATE -> Color.Green
                TaskStatus.FAILED -> Color.Red
                else -> Color.Cyan
            }
            Text("[${task.name}]", color = color)
        }
    }) { measurables, constraints ->
        val edgesPlaceable = measurables[0].measure(constraints)
        val nodePlaceables = measurables.drop(1).map { it.measure(constraints) }
        
        layout(layoutParams.width, layoutParams.height) {
            edgesPlaceable.place(0, 0)
            
            var i = 0
            for (v in graph.allVerticesIndices()) {
                val point = layoutParams.positions[v]!!
                val p = nodePlaceables[i++]
                val startX = max(0, kotlin.math.round(point.x).toInt() - p.width / 2)
                val startY = kotlin.math.round(point.y).toInt()
                p.place(startX, startY)
            }
        }
    }
}
