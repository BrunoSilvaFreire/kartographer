package io.kartographer.tasks.dart

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.NoInputs
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.Task
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** Stands in for a real test task: all that matters is that it declares test outputs. */
private class FakeTestTask(name: String) : Task<NoInputs, DartTestOutputs>(name) {
    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = DartTestOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs) =
        ExecutionResult.Success(buildOutputs())
}

/** A dependency that is not a test task at all, and must not be counted as a missing one. */
private class FakeBuildTask(name: String) : Task<NoInputs, io.kartographer.core.model.NoOutputs>(name) {
    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = io.kartographer.core.model.NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs) =
        ExecutionResult.Success(buildOutputs())
}

class TestAggregateTest {

    @Test
    fun `merges the suites that reported and names the ones that did not`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        writeJsonReport(File(projectDir, "results/a.json"), listOf("a first" to true, "a second" to true))
        writeJsonReport(File(projectDir, "results/b.json"), listOf("b first" to true, "b second" to false, "b third" to true))
        File(projectDir, "results/a.lcov").writeText("SF:lib/a.dart\nDA:1,1\nLF:4\nLH:3\nend_of_record\n")
        File(projectDir, "results/b.lcov").writeText("SF:lib/b.dart\nDA:1,1\nLF:6\nLH:3\nend_of_record\n")

        val dependencies = mapOf(
            "pkg/a/unit" to FakeTestTask("pkg/a/unit"),
            "pkg/b/unit" to FakeTestTask("pkg/b/unit"),
            // Ran, passed, wrote nothing — the case this whole task exists for.
            "pkg/c/unit" to FakeTestTask("pkg/c/unit"),
            "setup/tools" to FakeBuildTask("setup/tools"),
        )
        val produced = mapOf(
            "pkg/a/unit" to result(total = 2, passed = 2, json = "results/a.json", lcov = "results/a.lcov", coverage = 75.0),
            "pkg/b/unit" to result(total = 3, passed = 3, json = "results/b.json", lcov = "results/b.lcov", coverage = 50.0),
            "setup/tools" to emptyMap(),
        )

        val task = TestAggregate("test/report")
        task.outputDir.set("results")
        val (result, outputs) = runAggregate(task, projectDir, dependencies, produced)

        // The one that reported nothing fails the aggregate...
        assertTrue(result is ExecutionResult.Failure)
        assertTrue((result as ExecutionResult.Failure).error.message!!.contains("pkg/c/unit"))
        // ...without discarding what the others reported.
        assertEquals("5", outputs["totalCount"])
        assertEquals("5", outputs["passedCount"])
        assertEquals("2", outputs["suiteCount"])
        assertTrue(outputs["missingResults"]!!.contains("pkg/c/unit"))
        // A non-test dependency is not a missing test result.
        assertTrue(!outputs["missingResults"]!!.contains("setup/tools"))

        // 6 of 10 lines across the two merged reports.
        assertEquals("60.0", outputs["coveragePercent"])
        val merged = File(projectDir, "results/all.xml").readText()
        assertTrue(merged.startsWith("<?xml"))
        assertTrue(merged.contains("<testsuites"))
        assertTrue(merged.contains("pkg/a/unit") && merged.contains("pkg/b/unit"))
        // The merged report carries the individual tests, not just the counts.
        assertTrue(merged.contains("a first") && merged.contains("b third"))
    }

    @Test
    fun `excludes generated files from the merged denominator`(@TempDir tempDir: Path) = runBlocking {
        val projectDir = tempDir.toFile()
        File(projectDir, "results").mkdirs()
        File(projectDir, "results/a.lcov").writeText(
            "SF:lib/a.dart\nLF:4\nLH:4\nend_of_record\n" +
                "SF:lib/a.pb.dart\nLF:100\nLH:10\nend_of_record\n"
        )

        val task = TestAggregate("test/report")
        task.outputDir.set("results")
        task.coverageExclude.set(listOf("**/*.pb.dart"))
        val (result, outputs) = runAggregate(
            task,
            projectDir,
            mapOf("pkg/a/unit" to FakeTestTask("pkg/a/unit")),
            mapOf("pkg/a/unit" to result(total = 1, passed = 1, lcov = "results/a.lcov"))
        )

        assertTrue(result is ExecutionResult.Success)
        // Without the exclusion this would be 14/104 = 13.46%.
        assertEquals("100.0", outputs["coveragePercent"])
        assertTrue(!File(projectDir, "results/all.lcov").readText().contains("a.pb.dart"))
    }

    /** Runs the task and captures what it emitted, the way an observer would see it. */
    private suspend fun runAggregate(
        task: TestAggregate,
        projectDir: File,
        dependencies: Map<String, Task<*, *>>,
        produced: Map<String, Map<String, Any?>>,
    ): Pair<ExecutionResult<*>, Map<String, String>> {
        val emitted = mutableMapOf<String, String>()
        val context = ExecutionContext(
            projectDir = projectDir.toPath(),
            worker = Worker(WorkerId("test-worker")),
            observer = object : io.kartographer.core.model.ExecutionObserver {
                override fun onEvent(event: io.kartographer.core.model.ExecutionEvent) {
                    if (event is io.kartographer.core.model.ExecutionEvent.TaskOutputProduced) {
                        emitted[event.name] = event.value
                    }
                }
            },
            task = task,
            dependencyTasks = dependencies,
        )
        val result = withContext(ProviderResolution(context, produced)) {
            task.execute(context, task.buildInputs(context))
        }
        return result to emitted
    }

    private fun result(
        total: Int,
        passed: Int,
        failed: Int = 0,
        json: String? = null,
        lcov: String? = null,
        coverage: Double? = null,
    ): Map<String, Any?> = buildMap {
        put("totalCount", total)
        put("passedCount", passed)
        put("failedCount", failed)
        put("skippedCount", 0)
        put("durationMs", 10L)
        put("success", failed == 0)
        json?.let { put("jsonReportPath", it) }
        lcov?.let { put("coverageLcov", it) }
        coverage?.let { put("coveragePercent", it) }
    }

    /** A `dart test --file-reporter json` report with the given outcomes. */
    private fun writeJsonReport(file: File, tests: List<Pair<String, Boolean>>) {
        file.parentFile.mkdirs()
        val lines = mutableListOf(
            """{"protocolVersion":"0.1.1","runnerVersion":"1.30.0","pid":1,"type":"start","time":0}""",
            """{"suite":{"id":0,"platform":"vm","path":"test/sample_test.dart"},"type":"suite","time":0}"""
        )
        var id = 1
        var time = 0
        for ((name, passed) in tests) {
            lines.add(
                """{"test":{"id":$id,"name":"$name","suiteID":0,"groupIDs":[],""" +
                    """"metadata":{"skip":false,"skipReason":null},"line":$id,"column":1,""" +
                    """"url":"file:///test/sample_test.dart"},"type":"testStart","time":$time}"""
            )
            time += 10
            if (!passed) {
                lines.add(
                    """{"testID":$id,"error":"boom","stackTrace":"test/sample_test.dart 1:1","isFailure":true,"type":"error","time":$time}"""
                )
            }
            lines.add(
                """{"testID":$id,"result":"${if (passed) "success" else "failure"}","skipped":false,"hidden":false,"type":"testDone","time":$time}"""
            )
            id++
        }
        lines.add("""{"success":${tests.all { it.second }},"type":"done","time":$time}""")
        file.writeText(lines.joinToString("\n"))
    }
}
