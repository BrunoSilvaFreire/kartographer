package io.kartographer.daemon

import io.kartographer.core.*
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.RemoteWorkerClient
import io.kartographer.tasks.DefaultTask
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

class TestExecTask(name: String) : Task(name) {
    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        context.executeBash {
            command("echo")
            args("'Hello from integration test'")
        }
        return ExecutionResult.Success
    }
}

class DaemonIntegrationTest {

    @Test
    fun testDaemonTaskExecutionAndLogging() {
        runBlocking {
            val server = DaemonServer(0)
            server.start()
            val port = server.actualPort

            try {
                val task = TestExecTask("test-exec-task")

                val client = RemoteWorkerClient("localhost", port)
                val worker = Worker(
                    id = WorkerId("local-worker"),
                    labels = mapOf(),
                    capabilities = setOf("local-exec")
                )
                val context = ExecutionContext(
                    projectDir = Paths.get("").toAbsolutePath(),
                    worker = worker,
                    runId = "test-run-123"
                )

                val result = client.executeRemote(task, context)
                client.close()

                assertTrue(result is ExecutionResult.Success)

                val logDir = File(System.getProperty("user.home"), ".kartographer/logs/runs/test-run-123")
                val logFile = File(logDir, "test-exec-task.log")
                
                assertTrue(logFile.exists(), "Log file should have been created at: ${logFile.absolutePath}")
                val logContent = logFile.readText()
                assertTrue(logContent.contains("Hello from integration test"), "Log should contain executed command output")
                assertTrue(logContent.contains("=== Kartographer Task Execution Log ==="), "Log should contain task metadata header")

                logFile.delete()
                logDir.delete()
            } finally {
                server.stop()
            }
        }
    }
}
