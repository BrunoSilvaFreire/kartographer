package io.kartographer.tasks.go

import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult
import io.kartographer.core.Worker
import io.kartographer.core.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class GoTasksTest {

    @Test
    fun testGoBuildConfiguration() = runBlocking {
        val customBuildTask = object : GoBuild("mock-go-build") {
            override suspend fun execute(context: ExecutionContext): ExecutionResult {
                val outCmd = output?.let { "-o $it" } ?: ""
                val mainCmd = mainFile ?: "."
                commands.clear()
                exec("go build $outCmd $mainCmd")
                return ExecutionResult.Success
            }
        }.apply {
            output = "bin/myapp"
            mainFile = "main.go"
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        customBuildTask.execute(context)
        assertEquals(1, customBuildTask.commands.size)
        assertEquals("go build -o bin/myapp main.go", customBuildTask.commands[0])
    }

    @Test
    fun testGoTestConfiguration() = runBlocking {
        val customTestTask = object : GoTest("mock-go-test") {
            override suspend fun execute(context: ExecutionContext): ExecutionResult {
                commands.clear()
                exec("go test ${packages.joinToString(" ")}")
                return ExecutionResult.Success
            }
        }.apply {
            packages = mutableListOf("./cmd/...", "./pkg/...")
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        customTestTask.execute(context)
        assertEquals(1, customTestTask.commands.size)
        assertEquals("go test ./cmd/... ./pkg/...", customTestTask.commands[0])
    }
}
