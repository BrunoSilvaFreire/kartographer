package io.kartographer.daemon

import io.grpc.Status
import io.grpc.StatusException
import io.kartographer.core.daemon.BuildStamp
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
// `inputs.file(...)` and friends are extension functions on TaskInputs/TaskOutputs.
import io.kartographer.core.model.dir
import io.kartographer.core.model.file
import io.kartographer.core.plugins.PluginLoader
import io.kartographer.core.plugins.TaskSerializer
import io.kartographer.daemon.project.CompletionResolver
import io.kartographer.daemon.project.ProjectCache
import io.kartographer.daemon.project.ProjectDescription
import io.kartographer.daemon.proto.*
import io.kartographer.daemon.proto.KartographerDaemonGrpcKt.KartographerDaemonCoroutineImplBase
import io.kartographer.daemon.run.DaemonCapabilities
import io.kartographer.daemon.run.RunSession
import io.kartographer.daemon.run.RunTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

class KartographerDaemonService(
    private val projects: ProjectCache = ProjectCache(),
    val runTracker: RunTracker = RunTracker()
) : KartographerDaemonCoroutineImplBase(), Closeable {

    companion object {
        private val logger = LoggerFactory.getLogger(KartographerDaemonService::class.java)
    }

    private val startedAtMillis = System.currentTimeMillis()
    private val activeRuns = AtomicInteger(0)
    private val buildStamp = BuildStamp.ofRunningProcess()

    /** Set by [DaemonServer] so a Shutdown RPC can stop the transport it is answering on. */
    var onShutdownRequested: (() -> Unit)? = null

    /** True while a run is in flight, for the idle-shutdown timer. */
    val isBusy: Boolean
        get() = activeRuns.get() > 0

    override suspend fun getWorkerInfo(request: WorkerInfoRequest): WorkerInfoResponse =
        workerInfoResponse {
            workerId = "daemon-worker"
            labels.putAll(
                mapOf(
                    "os" to System.getProperty("os.name"),
                    "arch" to System.getProperty("os.arch")
                )
            )
            capabilities.addAll(DaemonCapabilities.ALL)
        }

    override suspend fun getDaemonInfo(request: DaemonInfoRequest): DaemonInfoResponse =
        daemonInfoResponse {
            version = buildStamp
            pid = ProcessHandle.current().pid()
            activeRuns = this@KartographerDaemonService.activeRuns.get()
            cachedProjects = projects.size
            uptimeMillis = System.currentTimeMillis() - startedAtMillis
        }

    override suspend fun shutdown(request: ShutdownRequest): ShutdownResponse {
        logger.info("Shutdown requested over RPC")
        // Answer first, then tear down: stopping the server inline would kill this response.
        Thread {
            Thread.sleep(100)
            onShutdownRequested?.invoke()
        }.apply { isDaemon = true }.start()
        return shutdownResponse { accepted = true }
    }

    override suspend fun describeProject(request: DescribeProjectRequest): DescribeProjectResponse =
        withProject(request.context) { ProjectDescription.of(it) }

    override suspend fun complete(request: CompleteRequest): CompleteResponse =
        try {
            withProject(request.context) { project ->
                completeResponse {
                    candidates.addAll(CompletionResolver.complete(project, request.wordsList, request.cword))
                }
            }
        } catch (e: Exception) {
            // A broken pipeline must not make the user's shell print a stack trace where a
            // completion list should be. Silence is the only acceptable failure mode here.
            logger.debug("Completion failed for {}: {}", request.context.projectDir, e.localizedMessage)
            completeResponse { }
        }

    override suspend fun queryRuns(request: QueryRunsRequest): QueryRunsResponse =
        runTracker.queryRuns(request)

    override fun runPipeline(requests: Flow<RunClientMessage>): Flow<RunServerMessage> = channelFlow {
        val outbound = Channel<RunServerMessage>(Channel.UNLIMITED)
        val started = CompletableDeferred<RunStart>()
        val earlyStdin = mutableListOf<String>()
        var session: RunSession? = null

        // Pump: task threads produce events, but this is the single writer to the gRPC flow,
        // which is what keeps their order intact.
        val pump = launch {
            for (message in outbound) {
                send(message)
            }
        }

        // Lazy so the job exists before anything can ask to cancel it.
        val runJob = launch(start = CoroutineStart.LAZY) {
            val start = started.await()
            activeRuns.incrementAndGet()
            try {
                val context = start.context
                projects.use(projectDirOf(context), context.pipeline.takeIf { it.isNotBlank() }) { project ->
                    val runSession = RunSession(project, start, outbound, runTracker)
                    synchronized(earlyStdin) {
                        session = runSession
                        earlyStdin.forEach { runSession.onStdin(it) }
                        earlyStdin.clear()
                    }
                    runSession.execute()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                outbound.trySend(
                    runServerMessage {
                        error = runError {
                            message = e.localizedMessage ?: e.toString()
                            exitCode = 1
                        }
                    }
                )
            } finally {
                activeRuns.decrementAndGet()
            }
        }

        val reader = launch {
            try {
                requests.collect { message ->
                    when (message.messageCase) {
                        RunClientMessage.MessageCase.START -> started.complete(message.start)
                        RunClientMessage.MessageCase.PROMPT_RESPONSE ->
                            session?.onPromptResponse(message.promptResponse.requestId, message.promptResponse.value)
                        RunClientMessage.MessageCase.STDIN_DATA -> {
                            val s = session
                            if (s != null) {
                                s.onStdin(message.stdinData.data)
                            } else {
                                synchronized(earlyStdin) {
                                    val cur = session
                                    if (cur != null) {
                                        cur.onStdin(message.stdinData.data)
                                    } else {
                                        earlyStdin.add(message.stdinData.data)
                                    }
                                }
                            }
                        }
                        // Ctrl-C in the CLI: cancelling the job unwinds the scheduler's
                        // supervisor scope, which kills the child processes with it.
                        RunClientMessage.MessageCase.CANCEL -> runJob.cancel()
                        else -> Unit
                    }
                }
            } finally {
                // The client hung up. Nothing will ever answer an outstanding prompt, so
                // release the tasks blocked on one instead of leaving them parked forever.
                session?.abandonPrompts("client disconnected")
                if (runJob.isActive) {
                    runJob.cancel()
                }
                if (!started.isCompleted) {
                    started.completeExceptionally(IllegalStateException("client closed the stream before starting a run"))
                }
            }
        }

        runJob.start()
        runJob.join()
        outbound.close()
        pump.join()
        reader.cancel()
    }

    override suspend fun executeTask(request: ExecuteTaskRequest): ExecuteTaskResponse {
        val runId = request.runId.ifBlank { "default-run" }
        val taskName = request.taskName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val logFile = resolveLogFile(runId, taskName)

        if (request.lifecycle == io.kartographer.daemon.proto.TaskLifecycle.TASK_SERVICE) {
            return executeTaskResponse {
                status = ExecuteTaskResponse.Status.FAILURE
                errorMessage = "Service task '${request.taskName}' cannot use unary remote execution."
                exitCode = 1
            }
        }

        return try {
            logFile.parentFile.mkdirs()
            logFile.writeText("=== Kartographer Task Execution Log ===\n")
            logFile.appendText("Task Type: ${request.taskType}\n")
            logFile.appendText("Task Name: ${request.taskName}\n")
            logFile.appendText("Run ID: $runId\n")
            logFile.appendText("Timestamp: ${java.time.Instant.now()}\n")
            logFile.appendText("=======================================\n\n")

            val projectDirFile = File(request.projectDir)
            val localPluginDir = File(projectDirFile, "karto")
            if (localPluginDir.isDirectory && File(localPluginDir, "build.gradle.kts").exists()) {
                val jarFile = PluginLoader.findLocalPluginJar(localPluginDir) ?: run {
                    val gradlewFile = PluginLoader.findGradlew(projectDirFile)
                    if (gradlewFile != null) {
                        PluginLoader.compileLocalPlugin(localPluginDir, gradlewFile)
                        PluginLoader.findLocalPluginJar(localPluginDir)
                    } else null
                }
                if (jarFile != null) {
                    PluginLoader.addPluginJar(jarFile)
                }
            }

            val clazz = PluginLoader.findTaskClass(request.taskType)
                ?: return executeTaskResponse {
                    status = ExecuteTaskResponse.Status.FAILURE
                    errorMessage = "Task type '${request.taskType}' not found on the daemon."
                    exitCode = 1
                    logPath = logFile.absolutePath
                }

            val constructor = try {
                clazz.getConstructor(String::class.java)
            } catch (e: NoSuchMethodException) {
                return executeTaskResponse {
                    status = ExecuteTaskResponse.Status.FAILURE
                    errorMessage = "Task type '${request.taskType}' must have a public constructor taking a single String argument (the task name)."
                    exitCode = 1
                    logPath = logFile.absolutePath
                }
            }
            val taskInstance = constructor.newInstance(request.taskName)

            TaskSerializer.deserializeProperties(taskInstance, request.properties)

            for (f in request.inputFilesList) taskInstance.inputs.file(f)
            for (d in request.inputDirsList) taskInstance.inputs.dir(d)
            for (f in request.outputFilesList) taskInstance.trackedOutputs.file(f)
            for (d in request.outputDirsList) taskInstance.trackedOutputs.dir(d)

            val redirect = ProcessBuilder.Redirect.appendTo(logFile)
            val context = ExecutionContext(
                projectDir = Paths.get(request.projectDir),
                environment = System.getenv() + request.environment,
                worker = Worker(
                    id = WorkerId("daemon-worker"),
                    labels = mapOf(
                        "os" to System.getProperty("os.name"),
                        "arch" to System.getProperty("os.arch")
                    ),
                    capabilities = setOf("local-exec")
                ),
                dryRun = request.dryRun,
                runId = runId,
                redirectOutput = redirect,
                redirectError = redirect
            ).let { it.copy(executionEnvironment = io.kartographer.local.HostExecutionEnvironment(it)) }

            val inputSet = taskInstance.buildInputsAny(context).also {
                if (request.inputSetJson.isNotBlank()) {
                    io.kartographer.core.model.InputSetCodec.applyCanonical(it, request.inputSetJson)
                }
            }
            when (val result = taskInstance.executeAny(context, inputSet)) {
                is ExecutionResult.Success<*> -> executeTaskResponse {
                    status = ExecuteTaskResponse.Status.SUCCESS
                    logPath = logFile.absolutePath
                    outputSetJson = io.kartographer.core.model.OutputSetCodec.canonical(result.outputs)
                }
                is ExecutionResult.UpToDate<*> -> executeTaskResponse {
                    status = ExecuteTaskResponse.Status.UP_TO_DATE
                    logPath = logFile.absolutePath
                    outputSetJson = io.kartographer.core.model.OutputSetCodec.canonical(result.outputs)
                }
                is ExecutionResult.Failure<*> -> executeTaskResponse {
                    status = ExecuteTaskResponse.Status.FAILURE
                    errorMessage = result.error.localizedMessage ?: "Unknown error"
                    exitCode = result.exitCode ?: 1
                    logPath = logFile.absolutePath
                    outputSetJson = io.kartographer.core.model.OutputSetCodec.canonical(result.outputs)
                }
            }
        } catch (t: Throwable) {
            logFile.appendText("\n[FATAL DAEMON ERROR] execution crashed: ${t.localizedMessage}\n")
            t.printStackTrace(logFile.printWriter())
            executeTaskResponse {
                status = ExecuteTaskResponse.Status.FAILURE
                errorMessage = "Daemon execution crash: ${t.localizedMessage}"
                exitCode = 1
                logPath = logFile.absolutePath
            }
        }
    }

    private suspend fun <R> withProject(context: ProjectContext, block: suspend (io.kartographer.daemon.project.LoadedProject) -> R): R =
        try {
            projects.use(projectDirOf(context), context.pipeline.takeIf { it.isNotBlank() }, block)
        } catch (e: IllegalArgumentException) {
            // A missing or unreadable pipeline is the caller's problem, not a daemon fault.
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message).withCause(e))
        } catch (e: Exception) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription(e.message).withCause(e))
        }

    private fun projectDirOf(context: ProjectContext): File =
        File(context.projectDir.ifBlank { System.getProperty("user.dir") })

    private fun resolveLogFile(runId: String, taskName: String): File {
        val primaryDir = File("/var/log/kartographer/runs/$runId")
        val fallbackDir = File(System.getProperty("user.home"), ".kartographer/logs/runs/$runId")

        return try {
            primaryDir.mkdirs()
            val testFile = File(primaryDir, ".write_test")
            testFile.writeText("test")
            testFile.delete()
            File(primaryDir, "$taskName.log")
        } catch (e: Exception) {
            fallbackDir.mkdirs()
            File(fallbackDir, "$taskName.log")
        }
    }

    override fun close() {
        projects.clear()
    }
}
