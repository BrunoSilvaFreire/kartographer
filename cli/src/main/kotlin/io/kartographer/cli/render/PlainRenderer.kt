package io.kartographer.cli.render

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import kotlinx.coroutines.channels.Channel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class PlainRenderer : Renderer {
    override val events = Channel<ExecutionEvent>(Channel.UNLIMITED)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private fun log(message: String, isError: Boolean = false) {
        val time = LocalTime.now().format(timeFormatter)
        if (isError) {
            System.err.println("[$time] $message")
        } else {
            println("[$time] $message")
        }
    }

    private val summary = LinkedHashMap<String, Pair<String, Map<String, String>>>()

    private fun printSummary() {
        if (summary.isEmpty()) return
        println("")
        println("Summary:")
        val width = summary.keys.maxOf { it.length }
        for ((task, entry) in summary) {
            val (status, outputs) = entry
            println("  ${task.padEnd(width)}  $status")
            for ((key, value) in outputs) {
                println("  ${" ".repeat(width)}    $key = $value")
            }
        }
    }

    override suspend fun render() {
        for (event in events) {
            when (event) {
                is ExecutionEvent.PipelineStarted -> {
                    log("Pipeline started with ${event.totalTasks} tasks.")
                }
                is ExecutionEvent.TaskQueued -> {
                    log("[${event.task.name}] Queued.")
                }
                is ExecutionEvent.TaskStarted -> {
                    log("[${event.task.name}] Started.")
                }
                is ExecutionEvent.TaskOutput -> {
                    log("[${event.task.name}] ${event.line}", isError = event.isError)
                }
                is ExecutionEvent.TaskOutputProduced -> {
                    val displayVal = if (event.secret) io.kartographer.core.model.SECRET_MASK else event.value
                    log("[${event.task.name}] Output [${event.name}]: $displayVal")
                }
                is ExecutionEvent.TaskCompleted -> {
                    val status = when (val res = event.result) {
                        is ExecutionResult.Success -> {
                            log("[${event.task.name}] Completed successfully.")
                            "ok"
                        }
                        is ExecutionResult.UpToDate -> {
                            log("[${event.task.name}] Up-to-date.")
                            "up-to-date"
                        }
                        is ExecutionResult.Failure -> {
                            val msg = res.error.localizedMessage ?: "Unknown error"
                            log("[${event.task.name}] Failed: $msg", isError = true)
                            "FAILED"
                        }
                    }
                    summary[event.task.name] = status to event.outputs
                }
                is ExecutionEvent.PromptRequested -> {
                    println("🔑 ${event.message}")
                }
                is ExecutionEvent.PromptCompleted -> {
                    // Done
                }
                is ExecutionEvent.PipelineCompleted -> {
                    printSummary()
                    when (event.result) {
                        is ExecutionResult.Success -> log("Pipeline finished successfully.")
                        is ExecutionResult.UpToDate -> log("Pipeline finished (up-to-date).")
                        is ExecutionResult.Failure -> {
                            log("Pipeline failed.", isError = true)
                        }
                    }
                    break // Exit loop
                }
            }
        }
    }
}
