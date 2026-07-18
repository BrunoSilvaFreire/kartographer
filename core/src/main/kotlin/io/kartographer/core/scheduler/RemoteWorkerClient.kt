package io.kartographer.core.scheduler

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Task
import io.kartographer.core.plugins.TaskSerializer
import io.kartographer.daemon.proto.*
import io.kartographer.daemon.proto.KartographerDaemonGrpcKt.KartographerDaemonCoroutineStub
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.TimeUnit

class RemoteWorkerClient(private val host: String, private val port: Int) : Closeable {
    companion object {
        private val logger = LoggerFactory.getLogger(RemoteWorkerClient::class.java)
    }

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .build()

    private val stub = KartographerDaemonCoroutineStub(channel)

    suspend fun executeRemote(task: Task, context: ExecutionContext): ExecutionResult {
        val serializedProps = TaskSerializer.serializeProperties(task)
        
        val request = executeTaskRequest {
            taskType = task::class.java.name
            taskName = task.name
            runId = context.runId ?: ""
            properties.putAll(serializedProps)
            inputFiles.addAll(task.inputs.files)
            inputDirs.addAll(task.inputs.dirs)
            outputFiles.addAll(task.outputs.files)
            outputDirs.addAll(task.outputs.dirs)
            projectDir = context.projectDir.toString()
            environment.putAll(context.environment)
            dryRun = context.dryRun
        }

        return try {
            val response = stub.executeTask(request)
            logger.info("[REMOTE] [{}] Execution complete on worker. Remote log file: {}", task.name, response.logPath)
            
            when (response.status) {
                ExecuteTaskResponse.Status.SUCCESS -> ExecutionResult.Success
                ExecuteTaskResponse.Status.UP_TO_DATE -> ExecutionResult.UpToDate
                ExecuteTaskResponse.Status.FAILURE, ExecuteTaskResponse.Status.UNRECOGNIZED, null -> {
                    ExecutionResult.Failure(
                        error = RuntimeException(response.errorMessage.ifBlank { "Remote task execution failed" }),
                        exitCode = response.exitCode
                    )
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = RuntimeException("gRPC call to remote worker failed: ${e.localizedMessage}", e)
            )
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
