package io.kartographer.tasks.dart

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.currentProviderResolution
import io.kartographer.core.model.emitOutput
import java.io.File

data class TestAggregateInputs(
    var outputDir: String,
    var reportName: String,
    var coverageExclude: List<String>,
    var minCoverage: Double?,
    var requireAll: Boolean,
    var generateJunit: Boolean,
    var generateHtml: Boolean,
    var ignoreFailures: Boolean,
) : InputSet()

class TestAggregateOutputs : TestOutputs() {
    /** How many test tasks reported. */
    val suiteCount = output<Int>("suiteCount")

    /** Test tasks in the closure that reported nothing at all. */
    val missingResults = output<String>("missingResults")

    /** One line per suite: counts and coverage. */
    val breakdown = output<String>("breakdown")
}

/**
 * One result for the whole run, built from the test tasks underneath it.
 *
 * It reads its dependencies' outputs rather than globbing the report directory, because a
 * glob cannot tell a task that produced no results from a file left over by last week's
 * run — and "everything that ran passed" is a different claim from "everything passed".
 * [missingResults] is the difference between them.
 */
open class TestAggregate(name: String) : Task<TestAggregateInputs, TestAggregateOutputs>(name) {

    /** The tasks (or groups of tasks) whose results this one collects. */
    fun aggregates(vararg paths: String) {
        dependsOn(*paths)
    }

    val outputDir = property<String> { set("test-results") }
    val reportName = property<String> { set("all") }

    /** Globs whose files leave the merged coverage denominator — generated code, mostly. */
    val coverageExclude = property<List<String>> { set(emptyList()) }

    /** Fails the run when merged coverage drops below this percentage. */
    val minCoverage = property<Double>()
        .withCLI("min-coverage", "Fail when merged line coverage falls below this percentage")

    /** Whether a test task that reported nothing fails the aggregate. */
    val requireAll = property<Boolean> { set(true) }

    val generateJunit = property<Boolean> { set(true) }
    val generateHtml = property<Boolean> { set(false) }
        .withCLI("html", "Generate a merged HTML report")
    val ignoreFailures = property<Boolean> { set(false) }

    init {
        // Its inputs are other tasks' results, which the state store cannot verify.
        alwaysRun()
        runAfterFailedDependencies()
    }

    override suspend fun buildInputs(context: ExecutionContext) = TestAggregateInputs(
        outputDir = outputDir.get(),
        reportName = reportName.get(),
        coverageExclude = coverageExclude.get(),
        minCoverage = minCoverage.orNull(),
        requireAll = requireAll.get(),
        generateJunit = generateJunit.get(),
        generateHtml = generateHtml.get(),
        ignoreFailures = ignoreFailures.get(),
    )

    override fun buildOutputs() = TestAggregateOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: TestAggregateInputs,
    ): ExecutionResult<TestAggregateOutputs> {
        val outputs = buildOutputs()
        val projectDir = context.projectDir.toFile()
        val produced = currentProviderResolution().producedOutputs

        // A dependency is a test task if it declares the outputs of one. Reading the schema
        // rather than the class keeps this working for any task that reports test results.
        val expected = context.dependencyTasks
            .filterValues { task -> TEST_OUTPUTS.all { it in task.buildOutputsAny().outputNames } }

        val suites = mutableListOf<Suite>()
        val missing = mutableListOf<String>()
        for ((id, _) in expected.entries.sortedBy { it.key }) {
            val values = produced[id]
            if (values == null || values["success"] == null) {
                missing.add(id)
                continue
            }
            suites.add(
                Suite(
                    id = id,
                    total = (values["totalCount"] as? Number)?.toInt() ?: 0,
                    passed = (values["passedCount"] as? Number)?.toInt() ?: 0,
                    failed = (values["failedCount"] as? Number)?.toInt() ?: 0,
                    skipped = (values["skippedCount"] as? Number)?.toInt() ?: 0,
                    durationMs = (values["durationMs"] as? Number)?.toLong() ?: 0L,
                    success = values["success"] as? Boolean ?: false,
                    coveragePercent = (values["coveragePercent"] as? Number)?.toDouble(),
                    json = (values["jsonReportPath"] as? String)?.let { resolve(projectDir, it) },
                    lcov = (values["coverageLcov"] as? String)?.let { resolve(projectDir, it) },
                )
            )
        }

        val reportDir = File(inputSet.outputDir).let { if (it.isAbsolute) it else File(projectDir, inputSet.outputDir) }
        reportDir.mkdirs()
        val junitFile = File(reportDir, "${inputSet.reportName}.xml")
        val htmlFile = File(reportDir, "${inputSet.reportName}.html")
        val lcovFile = File(reportDir, "${inputSet.reportName}.lcov")

        // Reports come from each suite's own JSON, so the merged JUnit and HTML carry real
        // test cases and failure traces rather than a re-parse of counts.
        val reports = suites.map { suite ->
            val parsed = suite.json
                ?.takeIf { it.exists() && it.length() > 0L }
                ?.let { DartTestReportParser.parseJsonReport(it) }
            DartTestReportParser.SuiteReport(
                name = suite.id,
                summary = parsed ?: DartTestRunSummary(
                    totalCount = suite.total,
                    passedCount = suite.passed,
                    failedCount = suite.failed,
                    skippedCount = suite.skipped,
                    durationMs = suite.durationMs,
                    success = suite.success,
                    testCases = emptyList()
                ),
                coveragePercent = suite.coveragePercent
            )
        }
        val merged = mergeLcov(suites.mapNotNull { it.lcov }, lcovFile)
        val coverage = if (merged) TestTaskSupport.filterCoverage(lcovFile, inputSet.coverageExclude) else null

        if (inputSet.generateJunit) {
            DartTestReportParser.generateJUnitXml(reports, junitFile)
        }
        if (inputSet.generateHtml) {
            DartTestReportParser.generateHtmlReport(reports, htmlFile, name, coverage)
        }

        context.emitOutput(outputs.suiteCount, suites.size)
        context.emitOutput(outputs.totalCount, suites.sumOf { it.total })
        context.emitOutput(outputs.passedCount, suites.sumOf { it.passed })
        context.emitOutput(outputs.failedCount, suites.sumOf { it.failed })
        context.emitOutput(outputs.skippedCount, suites.sumOf { it.skipped })
        context.emitOutput(outputs.durationMs, suites.sumOf { it.durationMs })
        context.emitOutput(outputs.success, suites.all { it.success } && missing.isEmpty())
        context.emitOutput(outputs.breakdown, breakdown(suites, missing))
        if (missing.isNotEmpty()) {
            context.emitOutput(
                outputs.missingResults,
                "${missing.size} of ${expected.size} test tasks produced no results: ${missing.joinToString(", ")}"
            )
        }
        if (junitFile.exists()) {
            context.emitOutput(outputs.junitReportPath, junitFile.relativeTo(projectDir).path)
        }
        if (htmlFile.exists()) {
            context.emitOutput(outputs.htmlReportPath, htmlFile.relativeTo(projectDir).path)
        }
        if (coverage != null) {
            context.emitOutput(outputs.coveragePercent, coverage.coveragePercent)
            context.emitOutput(outputs.coverageLcov, lcovFile.relativeTo(projectDir).path)
        }

        context.observer?.onEvent(ExecutionEvent.TaskOutput(this, breakdown(suites, missing), isError = false))

        val problems = buildList {
            val failedSuites = suites.filter { !it.success }
            if (failedSuites.isNotEmpty()) {
                add("${failedSuites.size} test task(s) failed: ${failedSuites.joinToString(", ") { it.id }}")
            }
            if (missing.isNotEmpty() && inputSet.requireAll) {
                add("${missing.size} test task(s) produced no results: ${missing.joinToString(", ")}")
            }
            val floor = inputSet.minCoverage
            if (floor != null && coverage != null && coverage.coveragePercent < floor) {
                add("Merged coverage ${coverage.coveragePercent}% is below the ${floor}% floor.")
            }
        }

        if (problems.isNotEmpty() && !inputSet.ignoreFailures) {
            return ExecutionResult.Failure(RuntimeException(problems.joinToString("; ")), 1, outputs)
        }
        return ExecutionResult.Success(outputs)
    }

    private fun resolve(projectDir: File, path: String): File =
        File(path).let { if (it.isAbsolute) it else File(projectDir, path) }

    private fun breakdown(suites: List<Suite>, missing: List<String>): String = buildString {
        val width = (suites.map { it.id.length } + missing.map { it.length } + listOf(4)).max()
        for (suite in suites) {
            val coverage = suite.coveragePercent?.let { " ${it}%" } ?: ""
            appendLine(
                "  ${suite.id.padEnd(width)}  ${if (suite.success) "ok" else "FAILED"}  " +
                    "${suite.passed}/${suite.total} passed" +
                    (if (suite.failed > 0) ", ${suite.failed} failed" else "") +
                    (if (suite.skipped > 0) ", ${suite.skipped} skipped" else "") +
                    coverage
            )
        }
        for (id in missing) {
            appendLine("  ${id.padEnd(width)}  NO RESULTS")
        }
    }.trimEnd()

    /**
     * Concatenates the per-task lcov files, keeping one record per source file.
     *
     * A file measured by two suites (the app's own run also sees its packages) would
     * otherwise be counted twice, and the merged percentage would be a number that describes
     * nothing.
     */
    private fun mergeLcov(lcovFiles: List<File>, target: File): Boolean {
        val present = lcovFiles.filter { it.exists() && it.length() > 0L }
        if (present.isEmpty()) return false

        val records = linkedMapOf<String, String>()
        for (file in present) {
            var source: String? = null
            val record = StringBuilder()
            file.forEachLine { line ->
                if (line.startsWith("SF:")) source = line.removePrefix("SF:").trim()
                record.append(line).append('\n')
                if (line.trim() == "end_of_record") {
                    source?.let { records.putIfAbsent(it, record.toString()) }
                    record.setLength(0)
                    source = null
                }
            }
        }
        target.parentFile?.mkdirs()
        target.writeText(records.values.joinToString(""))
        return true
    }

    private data class Suite(
        val id: String,
        val total: Int,
        val passed: Int,
        val failed: Int,
        val skipped: Int,
        val durationMs: Long,
        val success: Boolean,
        val coveragePercent: Double?,
        val json: File?,
        val lcov: File?,
    )

    companion object {
        private val TEST_OUTPUTS = listOf("totalCount", "passedCount", "failedCount", "success")
    }
}
