package io.kartographer.daemon.run

import io.kartographer.daemon.proto.QueryRunsRequest
import io.kartographer.daemon.proto.QueryRunsResponse
import io.kartographer.daemon.proto.RunInfo
import io.kartographer.daemon.proto.TaskExecutionInfo
import io.kartographer.daemon.proto.queryRunsResponse
import io.kartographer.daemon.proto.runInfo
import io.kartographer.daemon.proto.taskExecutionInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class RunTracker(private val maxRecentRuns: Int = 50) {

    data class TaskState(
        val invocationId: String,
        val taskName: String,
        @Volatile var state: String = "QUEUED",
        @Volatile var startedAtMillis: Long = 0L,
        @Volatile var completedAtMillis: Long = 0L,
        val outputs: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
        @Volatile var errorMessage: String = ""
    ) {
        fun toProto(): TaskExecutionInfo = taskExecutionInfo {
            invocationId = this@TaskState.invocationId
            taskName = this@TaskState.taskName
            state = this@TaskState.state
            startedAtMillis = this@TaskState.startedAtMillis
            completedAtMillis = this@TaskState.completedAtMillis
            outputs.putAll(this@TaskState.outputs)
            if (this@TaskState.errorMessage.isNotBlank()) {
                errorMessage = this@TaskState.errorMessage
            }
        }
    }

    data class ActiveRun(
        val runId: String,
        val projectDir: String,
        val targetTask: String,
        @Volatile var state: String = "RUNNING",
        val startedAtMillis: Long = System.currentTimeMillis(),
        @Volatile var completedAtMillis: Long = 0L,
        val tasks: ConcurrentHashMap<String, TaskState> = ConcurrentHashMap()
    ) {
        fun toProto(): RunInfo = runInfo {
            runId = this@ActiveRun.runId
            projectDir = this@ActiveRun.projectDir
            targetTask = this@ActiveRun.targetTask
            state = this@ActiveRun.state
            startedAtMillis = this@ActiveRun.startedAtMillis
            completedAtMillis = this@ActiveRun.completedAtMillis
            tasks.addAll(this@ActiveRun.tasks.values.map { it.toProto() })
        }
    }

    private val activeRuns = ConcurrentHashMap<String, ActiveRun>()
    private val completedRuns = ConcurrentLinkedDeque<ActiveRun>()

    fun onRunStarted(runId: String, projectDir: String, targetTask: String): ActiveRun {
        val run = ActiveRun(runId, projectDir, targetTask)
        activeRuns[runId] = run
        return run
    }

    fun onTaskQueued(runId: String, invocationId: String, taskName: String) {
        val run = activeRuns[runId] ?: return
        run.tasks.computeIfAbsent(invocationId) { TaskState(invocationId, taskName, state = "QUEUED") }
    }

    fun onTaskStarted(runId: String, invocationId: String, taskName: String) {
        val run = activeRuns[runId] ?: return
        val task = run.tasks.computeIfAbsent(invocationId) { TaskState(invocationId, taskName) }
        task.state = "RUNNING"
        task.startedAtMillis = System.currentTimeMillis()
    }

    fun onTaskOutputProduced(runId: String, invocationId: String, taskName: String, name: String, value: String, isSecret: Boolean) {
        val run = activeRuns[runId] ?: return
        val task = run.tasks.computeIfAbsent(invocationId) { TaskState(invocationId, taskName) }
        task.outputs[name] = if (isSecret) "***" else value
    }

    fun onTaskCompleted(
        runId: String,
        invocationId: String,
        taskName: String,
        state: String,
        outputs: Map<String, String>,
        errorMessage: String = ""
    ) {
        val run = activeRuns[runId] ?: return
        val task = run.tasks.computeIfAbsent(invocationId) { TaskState(invocationId, taskName) }
        task.state = state
        task.completedAtMillis = System.currentTimeMillis()
        task.outputs.putAll(outputs)
        task.errorMessage = errorMessage
    }

    fun onRunCompleted(runId: String, state: String) {
        val run = activeRuns.remove(runId) ?: return
        run.state = state
        run.completedAtMillis = System.currentTimeMillis()

        completedRuns.addFirst(run)
        while (completedRuns.size > maxRecentRuns) {
            completedRuns.removeLast()
        }
    }

    fun queryRuns(request: QueryRunsRequest): QueryRunsResponse {
        val normalizedFilterDir = request.projectDir.takeIf { it.isNotBlank() }?.let {
            runCatching { File(it).canonicalPath }.getOrDefault(File(it).absolutePath)
        }

        fun matchesFilter(run: ActiveRun): Boolean {
            if (normalizedFilterDir == null) return true
            val runCanonical = runCatching { File(run.projectDir).canonicalPath }.getOrDefault(File(run.projectDir).absolutePath)
            return runCanonical == normalizedFilterDir
        }

        val results = mutableListOf<RunInfo>()
        for (run in activeRuns.values) {
            if (matchesFilter(run)) {
                results.add(run.toProto())
            }
        }

        if (!request.activeOnly) {
            for (run in completedRuns) {
                if (matchesFilter(run)) {
                    results.add(run.toProto())
                }
            }
        }

        return queryRunsResponse {
            runs.addAll(results)
        }
    }
}
