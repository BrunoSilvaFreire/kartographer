package io.kartographer.tasks.go

import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class GoTasksTest {

    @Test
    fun testGoBuildConfiguration() = runBlocking {
        val capturedCommands = mutableListOf<String>()
        val mockEnv = object : ExecutionEnvironment {
            override suspend fun execute(command: String, workingDir: String, environment: Map<String, String>, requiresElevation: Boolean): Int {
                capturedCommands.add(command)
                return 0
            }
        }
        
        val customBuildTask = GoBuild("mock-go-build").apply {
            output = "bin/myapp"
            mainFile = "main.go"
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker,
            executionEnvironment = mockEnv
        )

        customBuildTask.execute(context)
        assertEquals(1, capturedCommands.size)
        assertEquals("go build -o bin/myapp main.go", capturedCommands[0])
    }

    @Test
    fun testGoTestConfiguration() = runBlocking {
        val capturedCommands = mutableListOf<String>()
        val mockEnv = object : ExecutionEnvironment {
            override suspend fun execute(command: String, workingDir: String, environment: Map<String, String>, requiresElevation: Boolean): Int {
                capturedCommands.add(command)
                return 0
            }
        }

        val customTestTask = GoTest("mock-go-test").apply {
            packages = mutableListOf("./cmd/...", "./pkg/...")
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker,
            executionEnvironment = mockEnv
        )

        customTestTask.execute(context)
        assertEquals(1, capturedCommands.size)
        assertEquals("go test ./cmd/... ./pkg/...", capturedCommands[0])
    }
}
