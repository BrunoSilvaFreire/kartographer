package io.kartographer.tasks.flutter

import io.kartographer.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FlutterTestTaskTest {

    private val capturedCommands = mutableListOf<String>()
    private val capturedEnv = mutableMapOf<String, String>()

    private val mockExecutionEnv = object : ExecutionEnvironment {
        override suspend fun execute(
            command: String,
            workingDir: String,
            environment: Map<String, String>,
            requiresElevation: Boolean
        ): Int {
            capturedCommands.add(command)
            capturedEnv.putAll(environment)
            return 0
        }
    }

    private fun createContext(): ExecutionContext {
        return ExecutionContext(
            projectDir = Files.createTempDirectory("flutter-task-test"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("flutter")),
            executionEnvironment = mockExecutionEnv
        )
    }

    @Test
    fun `default FlutterTest builds expected command`() = runBlocking {
        val task = FlutterTest("unit-test")
        val ctx = createContext()

        val result = task.execute(ctx)

        assertTrue(result is ExecutionResult.Success)
        val cmd = capturedCommands.first()
        assertTrue(cmd.startsWith("flutter test"))
        assertTrue(cmd.contains("--file-reporter json:"))
    }

    @Test
    fun `config profile sets CITYSCAPE_CONFIG dart-define and env var`() = runBlocking {
        val task = FlutterTest("config-test")
        task.config.set("dev")
        val ctx = createContext()

        task.execute(ctx)

        val cmd = capturedCommands.first()
        assertTrue(cmd.contains("--dart-define=CITYSCAPE_CONFIG=config/dev.yaml"))
        assertEquals("config/dev.yaml", capturedEnv["CITYSCAPE_CONFIG"])
    }

    @Test
    fun `filtering flags are passed to flutter test`() = runBlocking {
        val task = FlutterTest("filter-test")
        task.target.set("test/specific_test.dart")
        task.namePattern.set("Widget.*")
        task.plainName.set("sub")
        task.tags.set("smoke")
        task.excludeTags.set("backend")
        task.device.set("linux")
        task.failFast.set(true)
        task.timeout.set("30s")
        task.coverage.set(true)
        val ctx = createContext()

        task.execute(ctx)

        val cmd = capturedCommands.first()
        assertTrue(cmd.contains("test/specific_test.dart"))
        assertTrue(cmd.contains("--name") && cmd.contains("Widget.*"))
        assertTrue(cmd.contains("--plain-name sub"))
        assertTrue(cmd.contains("--tags smoke"))
        assertTrue(cmd.contains("--exclude-tags backend"))
        assertTrue(cmd.contains("-d linux"))
        assertTrue(cmd.contains("--fail-fast"))
        assertTrue(cmd.contains("--timeout 30s"))
        assertTrue(cmd.contains("--coverage"))
    }

    @Test
    fun `task outputs declare required fields`() {
        val task = FlutterTest("outputs-test")
        val outputs = task.buildOutputs()

        assertEquals("totalCount", outputs.totalCount.name)
        assertEquals("passedCount", outputs.passedCount.name)
        assertEquals("failedCount", outputs.failedCount.name)
        assertEquals("skippedCount", outputs.skippedCount.name)
        assertEquals("durationMs", outputs.durationMs.name)
        assertEquals("success", outputs.success.name)
        assertEquals("jsonReportPath", outputs.jsonReportPath.name)
        assertEquals("junitReportPath", outputs.junitReportPath.name)
        assertEquals("failureSummary", outputs.failureSummary.name)
        assertEquals("coveragePercent", outputs.coveragePercent.name)
        assertEquals("coverageLcov", outputs.coverageLcov.name)
        assertEquals("vmServiceUri", outputs.vmServiceUri.name)
        assertEquals("devToolsUri", outputs.devToolsUri.name)
    }

    @Test
    fun `enableCaching registers tracked inputs and outputs`() {
        val task = FlutterTest("cache-test")
        task.enableCaching()

        assertTrue(task.inputs.dirs.contains("lib"))
        assertTrue(task.inputs.dirs.contains("test"))
        assertTrue(task.customFilesToTrack.contains("pubspec.yaml"))
        assertTrue(task.customFilesToTrack.contains("pubspec.lock"))
        assertTrue(task.trackedOutputs.dirs.contains("build/test-results"))
    }

    @Test
    fun `FlutterTest skips cleanly when target directory does not exist`() = runBlocking {
        val task = FlutterTest("missing-target-test")
        task.targets.set(listOf("nonexistent_dir"))
        val ctx = createContext()

        val result = task.execute(ctx)

        assertTrue(result is ExecutionResult.Success)
        assertTrue(capturedCommands.isEmpty())
    }
}
