package io.kartographer.daemon

import io.grpc.Server
import io.grpc.ServerBuilder
import io.kartographer.core.*
import io.kartographer.core.model.*
import io.kartographer.core.plugins.PluginLoader
import io.kartographer.core.plugins.TaskSerializer
import io.kartographer.daemon.proto.*
import io.kartographer.daemon.proto.KartographerDaemonGrpcKt.KartographerDaemonCoroutineImplBase
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import kotlin.coroutines.EmptyCoroutineContext

class DaemonServer(private val port: Int) {
    companion object {
        private val logger = LoggerFactory.getLogger(DaemonServer::class.java)
    }

    private val server: Server = ServerBuilder
        .forPort(port)
        .addService(KartographerDaemonService())
        .build()

    val actualPort: Int
        get() = server.port

    fun start() {
        server.start()
        logger.info("Kartographer Daemon started on port {}", port)
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down Kartographer Daemon...")
            this@DaemonServer.stop()
            logger.info("Kartographer Daemon shut down.")
        })
    }

    fun stop() {
        server.shutdown()
    }

    fun blockUntilShutdown() {
        server.awaitTermination()
    }
}

class KartographerDaemonService : KartographerDaemonCoroutineImplBase() {

    override suspend fun getWorkerInfo(request: WorkerInfoRequest): WorkerInfoResponse {
        return workerInfoResponse {
            workerId = "daemon-worker"
            labels.putAll(mapOf(
                "os" to System.getProperty("os.name"),
                "arch" to System.getProperty("os.arch")
            ))
            capabilities.addAll(listOf("local-exec", "go", "gradle", "protoc"))
        }
    }

    override suspend fun executeTask(request: ExecuteTaskRequest): ExecuteTaskResponse {
        val runId = request.runId.ifBlank { "default-run" }
        val taskName = request.taskName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        
        // 1. Determine log file path with robust fallback
        val logFile = resolveLogFile(runId, taskName)
        
        return try {
            logFile.parentFile.mkdirs()
            
            // Write initial banner to log file
            logFile.writeText("=== Kartographer Task Execution Log ===\n")
            logFile.appendText("Task Type: ${request.taskType}\n")
            logFile.appendText("Task Name: ${request.taskName}\n")
            logFile.appendText("Run ID: $runId\n")
            logFile.appendText("Timestamp: ${java.time.Instant.now()}\n")
            logFile.appendText("=======================================\n\n")

            // Load local plugin if present in the project directory
            val projectDirFile = File(request.projectDir)
            val localPluginDir = File(projectDirFile, "karto")
            if (localPluginDir.exists() && localPluginDir.isDirectory && File(localPluginDir, "build.gradle.kts").exists()) {
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

            // 2. Load Task Class
            val clazz = PluginLoader.findTaskClass(request.taskType)
                ?: return executeTaskResponse {
                    status = ExecuteTaskResponse.Status.FAILURE
                    errorMessage = "Task type '${request.taskType}' not found on the daemon."
                    exitCode = 1
                    logPath = logFile.absolutePath
                }

            // 3. Instantiate Task
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

            // 4. Deserialize Properties
            TaskSerializer.deserializeProperties(taskInstance, request.properties)

            // 5. Sync inputs and outputs from request
            for (f in request.inputFilesList) taskInstance.inputs.file(f)
            for (d in request.inputDirsList) taskInstance.inputs.dir(d)
            for (f in request.outputFilesList) taskInstance.outputs.file(f)
            for (d in request.outputDirsList) taskInstance.outputs.dir(d)

            // 6. Build ExecutionContext with Redirect
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

            // 7. Execute Task
            when (val result = taskInstance.execute(context)) {
                is ExecutionResult.Success -> {
                    executeTaskResponse {
                        status = ExecuteTaskResponse.Status.SUCCESS
                        logPath = logFile.absolutePath
                    }
                }
                is ExecutionResult.UpToDate -> {
                    executeTaskResponse {
                        status = ExecuteTaskResponse.Status.UP_TO_DATE
                        logPath = logFile.absolutePath
                    }
                }
                is ExecutionResult.Failure -> {
                    executeTaskResponse {
                        status = ExecuteTaskResponse.Status.FAILURE
                        errorMessage = result.error.localizedMessage ?: "Unknown error"
                        exitCode = result.exitCode ?: 1
                        logPath = logFile.absolutePath
                    }
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

    private fun resolveLogFile(runId: String, taskName: String): File {
        val primaryDir = File("/var/log/kartographer/runs/$runId")
        val fallbackDir = File(System.getProperty("user.home"), ".kartographer/logs/runs/$runId")
        
        return try {
            // Attempt to write a test file in primary directory
            primaryDir.mkdirs()
            val testFile = File(primaryDir, ".write_test")
            testFile.writeText("test")
            testFile.delete()
            File(primaryDir, "$taskName.log")
        } catch (e: Exception) {
            // Fallback to user home directory
            fallbackDir.mkdirs()
            File(fallbackDir, "$taskName.log")
        }
    }
}
