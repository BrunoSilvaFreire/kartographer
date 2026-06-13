package io.kartographer.core.scheduler

import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult
import io.kartographer.core.Task
import io.kartographer.core.TaskId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ConcurrentScheduler(private val allTasks: Map<TaskId, Task>) {

    suspend fun execute(
        targetTask: Task,
        context: ExecutionContext,
        checkTopology: (Task) -> Boolean,
        onTaskStart: (Task) -> Unit,
        onTaskSuccess: (Task) -> Unit,
        onTaskFailure: (Task, Throwable, Int?) -> Unit
    ): ExecutionResult = supervisorScope {
        val dagScheduler = DagScheduler(allTasks)
        val resolvedDeps = dagScheduler.resolveDependencies()

        // 1. Collect target task and its transitive dependencies
        val executionSet = mutableSetOf<Task>()
        fun collect(t: Task) {
            if (executionSet.add(t)) {
                val deps = resolvedDeps[t] ?: emptySet()
                for (d in deps) {
                    collect(d)
                }
            }
        }
        collect(targetTask)

        // 2. Map tasks to their remaining dependencies within this execution set
        val remainingDeps = ConcurrentHashMap<Task, MutableSet<Task>>()
        for (task in executionSet) {
            val deps = (resolvedDeps[task] ?: emptySet()).filter { it in executionSet }
            remainingDeps[task] = deps.toMutableSet()
        }

        val mutex = Mutex()
        val runningJobs = ConcurrentHashMap<Task, Job>()
        val completedTasks = ConcurrentHashMap.newKeySet<Task>()
        var failedTask: Task? = null
        var failureResult: ExecutionResult.Failure? = null

        val scope = this

        fun checkAndSpawnReady() {
            scope.launch {
                mutex.withLock {
                    if (failedTask != null) return@withLock

                    for (task in executionSet) {
                        if (task !in completedTasks && task !in runningJobs.keys) {
                            val deps = remainingDeps[task] ?: emptySet()
                            if (deps.isEmpty()) {
                                onTaskStart(task)

                                val job = scope.launch taskJob@{
                                    // Check topology placement requirements
                                    if (!checkTopology(task)) {
                                        val err = RuntimeException("Task '${task.name}' topology requirements not satisfied.")
                                        onTaskFailure(task, err, 1)
                                        mutex.withLock {
                                            if (failedTask == null) {
                                                failedTask = task
                                                failureResult = ExecutionResult.Failure(err, 1)
                                            }
                                        }
                                        cancelAllJobs(runningJobs.values)
                                        return@taskJob
                                    }

                                    // Check if up-to-date
                                    if (task.isUpToDate(context)) {
                                        // Trigger callback to inform runner it is up-to-date
                                        // We will pass an UpToDate flag or trigger onTaskSuccess
                                        onTaskSuccess(task)
                                        mutex.withLock {
                                            completedTasks.add(task)
                                            for (otherTask in executionSet) {
                                                remainingDeps[otherTask]?.remove(task)
                                            }
                                        }
                                        checkAndSpawnReady()
                                        return@taskJob
                                    }

                                    // Execute task
                                    when (val result = task.execute(context)) {
                                        is ExecutionResult.Success -> {
                                            TaskStateStore.saveState(context, task)
                                            onTaskSuccess(task)
                                            mutex.withLock {
                                                completedTasks.add(task)
                                                for (otherTask in executionSet) {
                                                    remainingDeps[otherTask]?.remove(task)
                                                }
                                            }
                                            checkAndSpawnReady()
                                        }
                                        is ExecutionResult.UpToDate -> {
                                            onTaskSuccess(task)
                                            mutex.withLock {
                                                completedTasks.add(task)
                                                for (otherTask in executionSet) {
                                                    remainingDeps[otherTask]?.remove(task)
                                                }
                                            }
                                            checkAndSpawnReady()
                                        }
                                        is ExecutionResult.Failure -> {
                                            onTaskFailure(task, result.error, result.exitCode)
                                            mutex.withLock {
                                                if (failedTask == null) {
                                                    failedTask = task
                                                    failureResult = result
                                                }
                                            }
                                            cancelAllJobs(runningJobs.values)
                                        }
                                    }
                                }
                                runningJobs[task] = job
                            }
                        }
                    }
                }
            }
        }

        checkAndSpawnReady()

        // Wait for all spawned jobs to finish
        while (true) {
            val jobs = runningJobs.values
            if (jobs.isNotEmpty() && jobs.all { !it.isActive }) {
                break
            }
            delay(10)
        }

        failureResult ?: ExecutionResult.Success
    }

    private fun cancelAllJobs(jobs: Collection<Job>) {
        for (job in jobs) {
            if (job.isActive) {
                job.cancel()
            }
        }
    }
}
