package io.kartographer.core.exec

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CommandsTest {
    @Test
    fun `read commands can run during dry-run when explicitly enabled`() = runBlocking {
        var executions = 0
        val environment = object : ExecutionEnvironment {
            override suspend fun execute(
                command: String,
                workingDir: String,
                environment: Map<String, String>,
                requiresElevation: Boolean,
                onStdoutLine: ((String) -> Unit)?,
                onStderrLine: ((String) -> Unit)?
            ): Int {
                executions++
                onStdoutLine?.invoke("read-only result")
                return 0
            }
        }
        val context = ExecutionContext(
            projectDir = Path.of("."),
            worker = Worker(WorkerId("test")),
            executionEnvironment = environment,
            dryRun = true
        )

        assertEquals("", context.runCommand("gcloud", listOf("info")).stdout)
        assertEquals("read-only result", context.runCommand("gcloud", listOf("info"), executeDuringDryRun = true).stdout)
        assertEquals(1, executions)
    }
}
