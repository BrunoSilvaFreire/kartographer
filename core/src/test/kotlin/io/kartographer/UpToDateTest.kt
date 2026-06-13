package io.kartographer

import io.kartographer.core.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.core.scheduler.TaskStateStore
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry
import io.kartographer.tasks.DefaultTask
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UpToDateTest {

    @BeforeEach
    fun setup() {
        TaskRegistry.clear()
    }

    @Test
    fun testCustomUpToDateCheck() = runBlocking {
        var runCount = 0
        val t = task<DefaultTask>("custom-check-task") {
            upToDateWhen { _, _ -> true }
            action {
                runCount++
            }
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Files.createTempDirectory("kartographer-test"),
            worker = worker
        )

        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)
        val result = scheduler.execute(
            targetTask = t,
            context = context,
            checkTopology = { true },
            onTaskStart = {},
            onTaskSuccess = {},
            onTaskFailure = { _, _, _ -> }
        )

        assertTrue(result is ExecutionResult.Success)
        assertEquals(0, runCount) // Should be skipped
    }

    @Test
    fun testAutomaticStateCheck(@TempDir tempDir: Path) = runBlocking {
        val input = tempDir.resolve("input.txt")
        val output = tempDir.resolve("output.txt")

        Files.writeString(input, "initial content")

        var runCount = 0

        val t = task<DefaultTask>("hash-check-task") {
            inputs.file(tempDir.relativize(input).toString())
            outputs.file(tempDir.relativize(output).toString())
            action {
                runCount++
                Files.writeString(output, Files.readString(input) + " processed")
            }
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = tempDir,
            worker = worker
        )

        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)

        // 1st Execution: State doesn't exist yet, should execute
        val result1 = scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertTrue(result1 is ExecutionResult.Success)
        assertEquals(1, runCount)
        assertTrue(Files.exists(output))

        // 2nd Execution: Inputs/outputs have not changed, should be skipped
        val result2 = scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertTrue(result2 is ExecutionResult.Success)
        assertEquals(1, runCount) // count should still be 1

        // 3rd Execution: Modify input file, should execute again
        Files.writeString(input, "new content")
        val result3 = scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertTrue(result3 is ExecutionResult.Success)
        assertEquals(2, runCount)

        // 4th Execution: Delete output file, should execute again
        Files.delete(output)
        val result4 = scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertTrue(result4 is ExecutionResult.Success)
        assertEquals(3, runCount)
    }

    @Test
    fun testPropertyMatches(@TempDir tempDir: Path) = runBlocking {
        var runCount = 0
        var propVal = "v1"
        val t = task<DefaultTask>("prop-task") {
            upToDateWhenPropertyMatches("testProp") { propVal }
            action {
                runCount++
            }
        }
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(projectDir = tempDir, worker = worker)
        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)

        // 1st Run: executes
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)

        // 2nd Run: propVal hasn't changed, should be skipped
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)

        // 3rd Run: propVal changes, should execute
        propVal = "v2"
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(2, runCount)
    }

    @Test
    fun testCustomFilesMatch(@TempDir tempDir: Path) = runBlocking {
        val customFile = tempDir.resolve("custom.txt")
        Files.writeString(customFile, "content1")

        var runCount = 0
        val t = task<DefaultTask>("custom-files-task") {
            upToDateWhenFilesMatch("custom.txt")
            action {
                runCount++
            }
        }
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(projectDir = tempDir, worker = worker)
        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)

        // 1st Run: executes
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)

        // 2nd Run: content hasn't changed, skipped
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)

        // 3rd Run: modify custom.txt, executes
        Files.writeString(customFile, "content2")
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(2, runCount)
    }

    @Test
    fun testInputsNewerThanOutputs(@TempDir tempDir: Path) = runBlocking {
        val input = tempDir.resolve("input.txt")
        val output = tempDir.resolve("output.txt")
        Files.writeString(input, "in")
        Files.writeString(output, "out")

        var runCount = 0
        val t = task<DefaultTask>("newer-task") {
            inputs.file(tempDir.relativize(input).toString())
            outputs.file(tempDir.relativize(output).toString())
            upToDateWhenInputsNewerThanOutputs()
            action {
                runCount++
            }
        }
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(projectDir = tempDir, worker = worker)
        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)

        // Case 1: Input time is equal or older than output time (e.g. input modified at 1000, output modified at 2000)
        Files.setLastModifiedTime(input, java.nio.file.attribute.FileTime.fromMillis(1000))
        Files.setLastModifiedTime(output, java.nio.file.attribute.FileTime.fromMillis(2000))
        // Should be up-to-date and skip execution
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(0, runCount)

        // Case 2: Input time is newer than output time (e.g. input modified at 3000, output modified at 2000)
        Files.setLastModifiedTime(input, java.nio.file.attribute.FileTime.fromMillis(3000))
        // Should execute
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)
    }

    @Test
    fun testSystemPropertyMatch(@TempDir tempDir: Path) = runBlocking {
        System.clearProperty("kartographer.test.prop")

        var runCount = 0
        val t = task<DefaultTask>("sys-prop-task") {
            trackSystemProperty("kartographer.test.prop")
            upToDateWhen { context, task ->
                val helpers = io.kartographer.core.scheduler.UpToDateHelpers
                helpers.systemPropertiesMatch("kartographer.test.prop").isUpToDate(context, task)
            }
            action {
                runCount++
            }
        }
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(projectDir = tempDir, worker = worker)
        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)

        // 1. First execution (prop is not set / empty)
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)

        // 2. Second execution (prop still not set / empty) - should skip
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(1, runCount)

        // 3. Set property - should execute
        System.setProperty("kartographer.test.prop", "value1")
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(2, runCount)

        // 4. Property is same - should skip
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(2, runCount)

        // 5. Change property - should execute
        System.setProperty("kartographer.test.prop", "value2")
        scheduler.execute(t, context, { true }, {}, {}, { _, _, _ -> })
        assertEquals(3, runCount)

        System.clearProperty("kartographer.test.prop")
    }
}
