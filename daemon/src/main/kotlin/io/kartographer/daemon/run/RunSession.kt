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
import io.kartographer.core.scheduler.ConcurrentScheduler
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import io.kartographer.daemon.proto.taskOutputProduced
import java.nio.file.Path
import java.nio.file.Paths
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

        val context = ExecutionContext(
            projectDir = projectDir,
            // The daemon's own environment is whatever launched it, which is not the shell the
            // user typed in — the client's environment wins.
            environment = System.getenv() + start.context.environmentMap,
            worker = worker,
            observer = StreamObserver(runId),
            dryRun = start.dryRun,
            runId = runId,
            prompter = StreamPrompter(task = null),
            stdinChannel = stdinChannel
        )

        if (!collectElevationSecret(executionSet, context)) {
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
            throw cancelled
        } catch (e: Exception) {
            runTracker?.onRunCompleted(runId, "FAILED")
            throw e
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
                        is ExecutionResult.Success<*> -> "COMPLETED"
                        is ExecutionResult.UpToDate<*> -> "UP_TO_DATE"
                        is ExecutionResult.Failure<*> -> "FAILED"
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
                    dependencies.addAll(
                        event.graph.edgesFrom(index).keys.mapNotNull { event.graph.vertex(it)?.name }
                    )
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
        "gradle", "kubectl", "node", "protoc", "pulumi", "python"
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
