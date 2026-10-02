package io.kartographer.tasks.flutter

import io.kartographer.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
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
        assertTrue(cmd.contains("--name 'Widget.*'"))
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

    /**
     * The regression guard for a run that dies before writing a report — a compile error, a
     * crashed shader compiler. With no JSON to parse there are no failing test cases to
     * count, and the task used to emit `success = true` while returning a failure.
     */
    @Test
    fun `a crash with no report emits failure, not success`() = runBlocking {
        for (isolated in listOf(false, true)) {
            val task = FlutterTest("crashing-test")
            task.isolateTargets.set(isolated)

            val produced = mutableMapOf<String, String>()
            val projectDir = Files.createTempDirectory("flutter-task-crash")
            File(projectDir.resolve("test").toFile(), "").mkdirs()
            File(projectDir.resolve("test").toFile(), "a_test.dart").writeText("void main() {}")
            task.reportDir.set(projectDir.resolve("reports").toString())

            val ctx = ExecutionContext(
                projectDir = projectDir,
                worker = Worker(WorkerId("test-worker"), capabilities = setOf("flutter")),
                observer = object : ExecutionObserver {
                    override fun onEvent(event: ExecutionEvent) {
                        if (event is ExecutionEvent.TaskOutputProduced) produced[event.name] = event.value
                    }
                },
                executionEnvironment = object : ExecutionEnvironment {
                    override suspend fun execute(
                        command: String,
                        workingDir: String,
                        environment: Map<String, String>,
                        requiresElevation: Boolean
                    ): Int = 1
                },
                task = task
            )

            val result = task.execute(ctx)

            assertTrue(result is ExecutionResult.Failure, "isolateTargets=$isolated should fail")
            assertEquals("false", produced["success"], "isolateTargets=$isolated")
            assertEquals("1", produced["failedCount"], "isolateTargets=$isolated")
        }
    }
}
