package io.kartographer.daemon.run

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionObserver
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.core.model.TopologyRequirement
import io.kartographer.core.model.UserPrompter
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.core.logs.FileTaskLogStore
import io.kartographer.core.logs.TaskLogBackend
import io.kartographer.core.logs.TaskLogConfig
import io.kartographer.core.logs.TaskLogObserver
import io.kartographer.core.logs.TaskLogSink
import io.kartographer.core.logs.selectBackends
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.daemon.logs.EventLogSink
import io.kartographer.daemon.logs.JournaldSink
import io.kartographer.daemon.project.LoadedProject
import io.kartographer.daemon.project.Nodes
import io.kartographer.daemon.project.TaskProperties
import io.kartographer.daemon.proto.PromptKind
import io.kartographer.daemon.proto.RunServerMessage
import io.kartographer.daemon.proto.RunStart
import io.kartographer.daemon.proto.RunStatus
import io.kartographer.daemon.proto.pipelineCompleted
import io.kartographer.daemon.proto.pipelineStarted
import io.kartographer.daemon.proto.promptRequested
import io.kartographer.daemon.proto.resolvedNode
import io.kartographer.daemon.proto.runError
import io.kartographer.daemon.proto.runServerMessage
import io.kartographer.daemon.proto.taskCompleted
import io.kartographer.daemon.proto.taskOutput
import io.kartographer.daemon.proto.taskQueued
import io.kartographer.daemon.proto.taskStarted
import io.kartographer.daemon.proto.taskReady
import io.kartographer.daemon.proto.taskStopped
import io.kartographer.daemon.proto.resolvedDependency
import io.kartographer.daemon.proto.TaskExecutionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import io.kartographer.daemon.proto.taskOutputProduced
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One `karto run` invocation, executing on the daemon while its terminal lives in the CLI.
 *
 * Everything the local run used to do with the console now travels over the stream: execution
 * events go out as they happen, and a task that asks for input suspends until the CLI answers.
 */
class RunSession(
    private val project: LoadedProject,
    private val start: RunStart,
    private val outbound: Channel<RunServerMessage>,
    private val runTracker: RunTracker? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RunSession::class.java)
    }

    private val pendingPrompts = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val stdinChannel = Channel<String>(Channel.UNLIMITED)

    /** Delivers a CLI stdin chunk to running tasks. */
    fun onStdin(data: String) {
        stdinChannel.trySend(data)
    }

    /** Delivers a CLI answer to whichever task is blocked on it. */
    fun onPromptResponse(requestId: String, value: String) {
        pendingPrompts.remove(requestId)?.complete(value)
    }

    /** Fails every outstanding prompt, so a disconnected client cannot wedge a task forever. */
    fun abandonPrompts(reason: String) {
        pendingPrompts.keys.toList().forEach { id ->
            pendingPrompts.remove(id)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    suspend fun execute() {
        val target = project.nodes[TaskId(start.taskName)]
        if (target == null) {
            emit(runServerMessage { error = runError { message = "Node '${start.taskName}' not found."; exitCode = 1 } })
            return
        }

        val executionSet = Nodes.closure(target, project.nodes)

        try {
            TaskProperties.validateCliValues(executionSet, start.propertiesMap)
        } catch (e: IllegalArgumentException) {
            emit(runServerMessage { error = runError { message = e.message ?: "Invalid property"; exitCode = 1 } })
            return
        }

        val worker = Worker(
            id = WorkerId("daemon-worker"),
            labels = mapOf(
                "arch" to System.getProperty("os.arch"),
                "os" to System.getProperty("os.name")
            ),
            capabilities = DaemonCapabilities.ALL
        )

        val runId = UUID.randomUUID().toString()
        val projectDir = resolveProjectDir()
        runTracker?.onRunStarted(runId, projectDir.toString(), start.taskName)

        // The daemon's own environment is whatever launched it, which is not the shell the
        // user typed in — the client's environment wins. The same merged map drives logging.
        val mergedEnvironment = System.getenv() + start.context.environmentMap
        val logSinks = openTaskLogSinks(projectDir, TaskLogConfig.from(mergedEnvironment), runId)

        val context = ExecutionContext(
            projectDir = projectDir,
            environment = mergedEnvironment,
            worker = worker,
            observer = TaskLogObserver(StreamObserver(runId), runId, logSinks),
            dryRun = start.dryRun,
            runId = runId,
            prompter = StreamPrompter(task = null),
            stdinChannel = stdinChannel
        )

        if (!collectElevationSecret(executionSet, context)) {
            closeTaskLogSinks(logSinks)
            runTracker?.onRunCompleted(runId, "FAILED")
            return
        }

        val scheduler = ConcurrentScheduler(project.nodes)
        // Dispatchers.IO because a task blocked on a prompt parks its thread until the CLI
        // answers, and that thread must not be one of the few Default workers.
        val result = try {
            withContext(Dispatchers.IO) {
                // The values stay applied for the whole run: a task may read a property in
                // `execute` rather than in `buildInputs`, and materialize() only holds them
                // while inputs are built.
                TaskProperties.withCliValues(executionSet, start.propertiesMap) {
                    scheduler.execute(
                        targetNode = target,
                        context = context,
                        checkTopology = { satisfiesTopology(it, worker) },
                        materializeInputs = { task, taskContext ->
                            TaskProperties.materialize(task, taskContext, start.propertiesMap)
                        }
                    )
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runTracker?.onRunCompleted(runId, "CANCELLED")
            withContext(kotlinx.coroutines.NonCancellable) {
                emit(runServerMessage {
                    pipelineCompleted = pipelineCompleted {
                        status = RunStatus.RUN_CANCELLED
                        errorMessage = "Run cancelled"
                        exitCode = 130
                    }
                })
            }
            return
        } catch (e: Exception) {
            runTracker?.onRunCompleted(runId, "FAILED")
            throw e
        } finally {
            closeTaskLogSinks(logSinks)
        }

        val finalRunStatus = when (result) {
            is ExecutionResult.Success<*> -> "COMPLETED"
            is ExecutionResult.UpToDate<*> -> "UP_TO_DATE"
            is ExecutionResult.Failure<*> -> "FAILED"
        }
        runTracker?.onRunCompleted(runId, finalRunStatus)

        emit(runServerMessage { pipelineCompleted = result.toPipelineCompleted() })
    }

    /**
     * Reproduces the local pre-run sudo prompt: the CLI prints the banner and reads the
     * password on the real terminal, and the answer is stashed where
     * `HostExecutionEnvironment` looks for it.
     */
    private suspend fun collectElevationSecret(executionSet: Set<Node>, context: ExecutionContext): Boolean {
        val elevated = executionSet.filterIsInstance<Task<*, *>>().filter { it.requiresElevation }
        if (elevated.isEmpty()) return true

        val alreadyRoot = System.getenv("USER") == "root" || System.getProperty("user.name") == "root"
        if (alreadyRoot || System.getProperty("os.name").lowercase().contains("win")) return true

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingPrompts[requestId] = deferred

        emit(runServerMessage {
            promptRequested = promptRequested {
                this.requestId = requestId
                message = "Please enter your sudo password to authorize: "
                secret = true
                kind = PromptKind.PROMPT_SUDO_PASSWORD
                elevatedTasks.addAll(elevated.map { it.name })
            }
        })

        return try {
            context.variables["SUDO_PASSWORD"] = deferred.await()
            true
        } catch (e: Exception) {
            pendingPrompts.remove(requestId)
            emit(runServerMessage {
                error = runError { message = "Privilege escalation was not authorized: ${e.message}"; exitCode = 1 }
            })
            false
        }
    }

    /**
     * Where tasks run. An explicit `-d` always wins; otherwise a `project { rootDirectory }`
     * declared by the pipeline may redirect it, which is what the local CLI did.
     */
    private fun resolveProjectDir(): Path {
        val base = Paths.get(start.context.projectDir).toAbsolutePath()
        val declaredRoot = project.declaredProjectRoot
        if (start.context.projectDirExplicit || declaredRoot == null) {
            return base.normalize()
        }
        return base.resolve(declaredRoot).normalize()
    }

    /**
     * Opens this run's log sinks, or an empty list when logging is off or cannot be set up.
     *
     * A read-only project directory (or any other setup failure) must never fail the run, so
     * every failure degrades to "no logs" with a single warning.
     */
    private fun openTaskLogSinks(projectDir: Path, config: TaskLogConfig, runId: String): List<TaskLogSink> {
        if (config.backend == TaskLogBackend.OFF) return emptyList()
        return try {
            val store = FileTaskLogStore(projectDir.resolve(".kartographer/logs/runs"), config)
            store.prune(Instant.now())

            val osName = System.getProperty("os.name") ?: ""
            // TODO: skip native probes when backend=FILE, and cache JournaldSink's loaded library instead of Native.load on every probe/open per run.
            val journaldAvailable = JournaldSink.isAvailable()
            val eventLogAvailable = EventLogSink.isAvailable()
            if (config.backend == TaskLogBackend.JOURNALD && !journaldAvailable) {
                logger.warn("KARTO_LOG_BACKEND=journald requested but journald is unavailable; writing file logs only.")
            }
            if (config.backend == TaskLogBackend.EVENTLOG && !eventLogAvailable) {
                logger.warn("KARTO_LOG_BACKEND=eventlog requested but the Windows Event Log is unavailable; writing file logs only.")
            }

            selectBackends(config, osName, journaldAvailable, eventLogAvailable).mapNotNull { backend ->
                when (backend) {
                    TaskLogBackend.FILE -> store.openRun(runId, Instant.now())
                    TaskLogBackend.JOURNALD -> JournaldSink.open()
                    TaskLogBackend.EVENTLOG -> EventLogSink.open()
                    TaskLogBackend.AUTO, TaskLogBackend.OFF -> null
                }
            }
        } catch (e: Exception) {
            logger.warn("Task logging is unavailable for this run: {}", e.toString())
            emptyList()
        }
    }

    private fun closeTaskLogSinks(sinks: List<TaskLogSink>) {
        for (sink in sinks) runCatching { sink.close() }
    }

    private fun satisfiesTopology(node: Node, worker: Worker): Boolean {
        if (node !is Task<*, *>) return true
        return node.topologyRequirements.all { requirement ->
            when (requirement) {
                is TopologyRequirement.RequiresLabel -> worker.labels[requirement.key] == requirement.value
                is TopologyRequirement.RequiresCapability -> requirement.capability in worker.capabilities
                // Local-only is satisfied by definition here, and preferences never block.
                is TopologyRequirement.LocalOnly -> true
                is TopologyRequirement.PrefersLabel -> true
            }
        }
    }

    private fun emit(message: RunServerMessage) {
        // The channel is unlimited, so this never drops and never suspends; ordering is what
        // matters, and a single producer per session preserves it.
        outbound.trySend(message)
    }

    /** Bridges scheduler events onto the wire. */
    private inner class StreamObserver(private val runId: String) : ExecutionObserver {
        override fun onEvent(event: ExecutionEvent) {
            val message = when (event) {
                is ExecutionEvent.PipelineStarted -> runServerMessage {
                    pipelineStarted = pipelineStarted {
                        totalTasks = event.totalTasks
                        graph.addAll(graphOf(event))
                    }
                }
                is ExecutionEvent.TaskQueued -> {
                    runTracker?.onTaskQueued(runId, event.task.executionId, event.task.name)
                    runServerMessage { taskQueued = taskQueued { task = event.task.name; invocationId = event.task.executionId } }
                }
                is ExecutionEvent.TaskStarted -> {
                    runTracker?.onTaskStarted(runId, event.task.executionId, event.task.name)
                    runServerMessage { taskStarted = taskStarted { task = event.task.name; invocationId = event.task.executionId } }
                }
                is ExecutionEvent.TaskReady -> {
                    runTracker?.onTaskReady(runId, event.task.executionId, event.task.name, event.outputs)
                    runServerMessage {
                        taskReady = taskReady {
                            task = event.task.name
                            invocationId = event.task.executionId
                            outputs.putAll(event.outputs)
                        }
                    }
                }
                is ExecutionEvent.TaskStopped -> {
                    runTracker?.onTaskStopped(runId, event.task.executionId, event.task.name, event.outputs)
                    runServerMessage {
                        taskStopped = taskStopped {
                            task = event.task.name
                            invocationId = event.task.executionId
                            outputs.putAll(event.outputs)
                        }
                    }
                }
                is ExecutionEvent.TaskOutput -> runServerMessage {
                    taskOutput = taskOutput {
                        task = event.task.name
                        line = event.line
                        isError = event.isError
                        invocationId = event.task.executionId
                    }
                }
                is ExecutionEvent.TaskOutputProduced -> {
                    runTracker?.onTaskOutputProduced(runId, event.task.executionId, event.task.name, event.name, event.value, event.secret)
                    runServerMessage {
                        taskOutputProduced = taskOutputProduced {
                            task = event.task.name
                            name = event.name
                            value = if (event.secret) io.kartographer.core.model.SECRET_MASK else event.value
                            isSecret = event.secret
                            invocationId = event.task.executionId
                        }
                    }
                }
                is ExecutionEvent.TaskCompleted -> {
                    val taskState = when (event.result) {
                        is ExecutionResult.Success<*>, is ExecutionResult.UpToDate<*> -> TaskExecutionState.TASK_COMPLETED
                        is ExecutionResult.Failure<*> -> TaskExecutionState.TASK_FAILED
                    }
                    val errMsg = (event.result as? ExecutionResult.Failure<*>)?.error?.localizedMessage ?: ""
                    runTracker?.onTaskCompleted(runId, event.task.executionId, event.task.name, taskState, event.outputs, errMsg)
                    runServerMessage {
                        taskCompleted = taskCompleted {
                            task = event.task.name
                            status = event.result.toRunStatus()
                            (event.result as? ExecutionResult.Failure<*>)?.let { failure ->
                                errorMessage = failure.error.localizedMessage ?: "Unknown error"
                                exitCode = failure.exitCode ?: 1
                            }
                            outputs.putAll(event.outputs)
                            invocationId = event.task.executionId
                        }
                    }
                }
                // Prompt traffic is driven by StreamPrompter, which owns the request ids; the
                // observer's own prompt events would carry none and are redundant here.
                is ExecutionEvent.PromptRequested -> return
                ExecutionEvent.PromptCompleted -> return
                // The session emits the terminal message itself, once execute() returns.
                is ExecutionEvent.PipelineCompleted -> return
            }
            emit(message)
        }

        // The scheduler's graph already has edges pointing from each node to its direct
        // dependencies, so this is a straight translation rather than another traversal.
        private fun graphOf(event: ExecutionEvent.PipelineStarted) =
            event.graph.allVerticesIndices().mapNotNull { index ->
                val node = event.graph.vertex(index) ?: return@mapNotNull null
                resolvedNode {
                    name = node.name
                    invocationId = node.executionId
                    displayName = node.name
                    lifecycle = if (node.lifecycle == io.kartographer.core.model.TaskLifecycle.SERVICE)
                        io.kartographer.daemon.proto.TaskLifecycle.TASK_SERVICE
                    else io.kartographer.daemon.proto.TaskLifecycle.TASK_FINITE
                    dependencies.addAll(event.graph.edgesFrom(index).mapNotNull { (dependencyIndex, condition) ->
                        val dependency = event.graph.vertex(dependencyIndex) ?: return@mapNotNull null
                        resolvedDependency {
                            invocationId = dependency.executionId
                            displayName = dependency.name
                            this.condition = if (condition == io.kartographer.core.model.DependencyCondition.READY)
                                io.kartographer.daemon.proto.DependencyCondition.DEPENDENCY_READY
                            else io.kartographer.daemon.proto.DependencyCondition.DEPENDENCY_COMPLETED
                        }
                    })
                }
            }
    }

    /**
     * A [UserPrompter] whose console is at the other end of the stream.
     *
     * [UserPrompter.prompt] is not a suspending function, so this blocks the calling thread —
     * exactly as `ConsoleUserPrompter` does while waiting on `System.console()`. Runs are
     * dispatched on [Dispatchers.IO] for that reason.
     */
    private inner class StreamPrompter(private val task: Node?) : UserPrompter {
        override fun forTask(task: Node): UserPrompter = StreamPrompter(task)

        override fun prompt(message: String, secret: Boolean): String {
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            pendingPrompts[requestId] = deferred
            // Read outside the builder: inside it, `task` would resolve to the message field.
            val taskName = task?.name
            val invocationId = task?.executionId

            emit(runServerMessage {
                promptRequested = promptRequested {
                    this.requestId = requestId
                    taskName?.let { this.task = it }
                    invocationId?.let { this.invocationId = it }
                    this.message = message
                    this.secret = secret
                    kind = PromptKind.PROMPT_GENERIC
                }
            })

            return try {
                runBlocking { deferred.await() }
            } catch (e: Exception) {
                pendingPrompts.remove(requestId)
                logger.warn("Prompt '{}' was not answered: {}", message, e.localizedMessage)
                ""
            }
        }
    }
}

/** Capabilities the daemon advertises, matching the task modules on its classpath. */
object DaemonCapabilities {
    val ALL = setOf(
        "local-exec", "ansible", "apt", "buf", "dart", "docker", "flutter", "gh", "git", "go",
        "gcloud", "gradle", "kubectl", "node", "protoc", "pulumi", "python"
    )
}

private fun ExecutionResult<*>.toRunStatus(): RunStatus = when (this) {
    is ExecutionResult.Success<*> -> RunStatus.RUN_SUCCESS
    is ExecutionResult.UpToDate<*> -> RunStatus.RUN_UP_TO_DATE
    is ExecutionResult.Failure<*> -> RunStatus.RUN_FAILURE
}

private fun ExecutionResult<*>.toPipelineCompleted() = pipelineCompleted {
    status = this@toPipelineCompleted.toRunStatus()
    (this@toPipelineCompleted as? ExecutionResult.Failure<*>)?.let { failure ->
        errorMessage = failure.error.localizedMessage ?: "Unknown error"
        exitCode = failure.exitCode ?: 1
    }
}
