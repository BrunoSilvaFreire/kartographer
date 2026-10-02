package io.kartographer.tasks.dart

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.emitOutput
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * What every test task reports, whichever SDK ran the tests.
 *
 * `dart test` and `flutter test` need separate tasks — different flags, different devices,
 * different coverage plumbing — but they answer the same questions, and a consumer reading
 * `failedCount` should not have to care which one produced it.
 */
abstract class TestOutputs : OutputSet() {
    val totalCount = output<Int>("totalCount")
    val passedCount = output<Int>("passedCount")
    val failedCount = output<Int>("failedCount")
    val skippedCount = output<Int>("skippedCount")
    val durationMs = output<Long>("durationMs")
    val success = output<Boolean>("success")
    val jsonReportPath = output<String>("jsonReportPath")
    val junitReportPath = output<String>("junitReportPath")
    val htmlReportPath = output<String>("htmlReportPath")
    val logPath = output<String>("logPath")
    val failureSummary = output<String>("failureSummary")
    val coveragePercent = output<Double>("coveragePercent")
    val coverageLcov = output<String>("coverageLcov")
}

/** Where one test run writes its reports. */
data class TestReportPaths(
    val projectDir: File,
    val reportDir: File,
    val baseName: String,
    val json: File,
    val junit: File,
    val html: File,
    val log: File,
    val lcov: File
) {
    /** The per-file report of an isolated run — one `flutter test` invocation per test file. */
    fun isolatedJson(index: Int, testFile: File): File =
        File(reportDir, "${baseName}_${index}_${testFile.nameWithoutExtension}.json")

    /**
     * The per-file coverage of an isolated run. Every invocation would otherwise write the
     * same [lcov], leaving only the last test file's data; these parts are merged into it.
     */
    fun isolatedLcov(index: Int, testFile: File): File =
        File(reportDir, "${baseName}_${index}_${testFile.nameWithoutExtension}.lcov.info")

    companion object {
        fun resolve(
            projectDir: File,
            reportDir: String?,
            baseName: String,
            coveragePath: String?
        ): TestReportPaths {
            val dirName = reportDir ?: "build/test-results"
            val resolvedDir = File(dirName).let { if (it.isAbsolute) it else File(projectDir, dirName) }
            resolvedDir.mkdirs()
            val lcov = coveragePath
                ?.let { File(it).let { f -> if (f.isAbsolute) f else File(projectDir, it) } }
                ?: File(resolvedDir, "lcov.info")
            return TestReportPaths(
                projectDir = projectDir,
                reportDir = resolvedDir,
                baseName = baseName,
                json = File(resolvedDir, "$baseName.json"),
                junit = File(resolvedDir, "$baseName.xml"),
                html = File(resolvedDir, "$baseName.html"),
                log = File(resolvedDir, "$baseName.log"),
                lcov = lcov
            )
        }
    }
}

/** The reporting knobs [TestTaskSupport.publish] needs, gathered from a task's input set. */
data class TestPublishOptions(
    val taskName: String,
    val generateJunit: Boolean,
    val generateHtml: Boolean,
    val coverage: Boolean,
    val coverageExclude: List<String> = emptyList(),
    val minCoverage: Double? = null,
    val ignoreFailures: Boolean = false
)

/**
 * The half of a test task that has nothing to do with which tool ran the tests.
 *
 * This used to be copy-pasted into `DartTest.execute` and twice into `FlutterTest.execute`,
 * and the copies drifted: the isolated-target path lost the "no report was written" fallback,
 * so a run that crashed before producing any JSON reported `success = true` while the task
 * itself failed. Having one copy is what stops that from coming back.
 */
object TestTaskSupport {

    /** Test files under [targets], recursively, in a stable order. */
    fun findTestFiles(baseDir: File, targets: List<String>): List<File> {
        if (targets.isEmpty()) {
            val defaultDir = File(baseDir, "test")
            return if (defaultDir.exists()) testFilesIn(defaultDir) else emptyList()
        }
        val result = mutableListOf<File>()
        for (target in targets) {
            val file = File(baseDir, target)
            when {
                file.isDirectory -> result.addAll(testFilesIn(file))
                file.isFile -> result.add(file)
                target.contains("*") -> {
                    val parts = target.split('/', '\\')
                    val parentPath = parts.dropLast(1).joinToString("/")
                    val parentDir = if (parentPath.isEmpty()) baseDir else File(baseDir, parentPath)
                    val regex = Regex("^" + parts.last().replace(".", "\\.").replace("*", ".*") + "$")
                    parentDir.takeIf { it.isDirectory }
                        ?.listFiles { f -> f.isFile && regex.matches(f.name) }
                        ?.sortedBy { it.path }
                        ?.let { result.addAll(it) }
                }
                else -> result.add(file)
            }
        }
        return result.distinct()
    }

    private fun testFilesIn(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith("_test.dart") }.sortedBy { it.path }.toList()

    /**
     * Whether running [targets] could produce anything.
     *
     * A named `.dart` target is taken on trust — it may be generated — but a directory with
     * no test files in it means the task has nothing to do, which is not a failure.
     */
    fun hasTests(baseDir: File, targets: List<String>): Boolean = targets.isEmpty() || targets.any { target ->
        if (target.endsWith(".dart")) true
        else File(baseDir, target).let { it.isFile || (it.isDirectory && testFilesIn(it).isNotEmpty()) }
    }

    /** A truncating writer for the task log, or null when the task does not keep one. */
    fun logWriter(paths: TestReportPaths, enabled: Boolean): PrintWriter? {
        if (!enabled) return null
        paths.log.writeText("")
        return PrintWriter(FileOutputStream(paths.log, true), true)
    }

    /**
     * The run's summary, from however many JSON reports it produced.
     *
     * [processFailed] is the tool's own exit status, and it outranks the reports: a run that
     * dies before it can write a report — a compile error, a crashed shader compiler, a
     * killed process — has no failing test cases to count, and must still not come back as
     * a success. That inference is the reason this function exists.
     */
    fun summarize(jsonFiles: List<File>, processFailed: Boolean): DartTestRunSummary {
        val summaries = jsonFiles
            .filter { it.exists() && it.length() > 0L }
            .map { DartTestReportParser.parseJsonReport(it) }

        if (summaries.isEmpty()) {
            return DartTestRunSummary(
                totalCount = 0,
                passedCount = 0,
                failedCount = if (processFailed) 1 else 0,
                skippedCount = 0,
                durationMs = 0L,
                success = !processFailed,
                testCases = emptyList()
            )
        }

        val combined = DartTestReportParser.combineSummaries(summaries)
        if (processFailed && combined.success) {
            // Every test that reported passed, but the tool still failed — a target that
            // never got to run, or a crash after the last test.
            return combined.copy(success = false, failedCount = combined.failedCount + 1)
        }
        return combined
    }

    /**
     * Merges lcov tracefiles into [into], summing the hits of a line that several parts
     * report. Concatenation would not do: the report parser sums one record per `SF:`
     * block, so a file measured by three test runs would count three times over.
     *
     * The parts are deleted once merged — they are an implementation detail of an
     * isolated run, not a report anybody reads.
     */
    fun mergeCoverage(parts: List<File>, into: File) {
        val present = parts.filter { it.exists() && it.length() > 0L }
        if (present.isEmpty()) return

        val merged = LinkedHashMap<String, MutableMap<Int, Int>>()
        for (part in present) {
            for (file in DartTestReportParser.parseLcovCoverage(part)?.files.orEmpty()) {
                val lines = merged.getOrPut(file.path) { LinkedHashMap() }
                for ((line, hits) in file.lineHits) {
                    lines[line] = (lines[line] ?: 0) + hits
                }
            }
        }

        into.parentFile?.mkdirs()
        into.writeText(
            buildString {
                for ((path, lines) in merged) {
                    append("SF:").append(path).append('\n')
                    for ((line, hits) in lines.entries.sortedBy { it.key }) {
                        append("DA:").append(line).append(',').append(hits).append('\n')
                    }
                    append("LF:").append(lines.size).append('\n')
                    append("LH:").append(lines.count { it.value > 0 }).append('\n')
                    append("end_of_record\n")
                }
            }
        )
        present.forEach { it.delete() }
    }

    /**
     * Drops [excludes] from [lcov], rewriting the file, and returns what is left.
     *
     * Filtering the file rather than only the percentage keeps the number, the lcov and any
     * HTML built from it telling the same story. Generated code is the case this is for:
     * 8,700 lines of protobuf accessors in the denominator say nothing about the tests.
     */
    fun filterCoverage(lcov: File, excludes: List<String>): LcovCoverageSummary? {
        if (excludes.isNotEmpty() && lcov.exists() && lcov.length() > 0L) {
            val matchers = excludes.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
            val kept = StringBuilder()
            val record = StringBuilder()
            var excluded = false
            lcov.forEachLine { line ->
                if (line.startsWith("SF:")) {
                    val path = line.removePrefix("SF:").trim()
                    val asPath = Paths.get(path)
                    val fileName = asPath.fileName ?: asPath
                    excluded = matchers.any { it.matches(asPath) || it.matches(fileName) }
                }
                if (!excluded) record.append(line).append('\n')
                if (line.trim() == "end_of_record") {
                    if (!excluded) kept.append(record)
                    record.setLength(0)
                    excluded = false
                }
            }
            if (record.isNotEmpty() && !excluded) kept.append(record)
            lcov.writeText(kept.toString())
        }
        return DartTestReportParser.parseLcovCoverage(lcov)
    }

    /**
     * Writes the reports, emits every output, and decides whether the task failed.
     *
     * [processFailure] is the tool invocation's own result, so a task that crashed reports a
     * failure even when its (missing or partial) reports would have looked clean.
     */
    suspend fun <O : TestOutputs> publish(
        context: ExecutionContext,
        outputs: O,
        summary: DartTestRunSummary,
        paths: TestReportPaths,
        options: TestPublishOptions,
        processFailure: ExecutionResult.Failure<*>? = null
    ): ExecutionResult<O> {
        val coverage = if (options.coverage) {
            filterCoverage(paths.lcov, options.coverageExclude)
        } else null

        if (options.generateJunit) {
            DartTestReportParser.generateJUnitXml(summary, options.taskName, paths.junit)
        }
        if (options.generateHtml) {
            DartTestReportParser.generateHtmlReport(
                suites = listOf(DartTestReportParser.SuiteReport(options.taskName, summary, coverage?.coveragePercent, coverage)),
                htmlFile = paths.html,
                title = options.taskName,
                coverage = coverage
            )
        }

        context.emitOutput(outputs.totalCount, summary.totalCount)
        context.emitOutput(outputs.passedCount, summary.passedCount)
        context.emitOutput(outputs.failedCount, summary.failedCount)
        context.emitOutput(outputs.skippedCount, summary.skippedCount)
        context.emitOutput(outputs.durationMs, summary.durationMs)
        context.emitOutput(outputs.success, summary.success)

        for ((output, file) in listOf(
            outputs.jsonReportPath to paths.json,
            outputs.junitReportPath to paths.junit,
            outputs.htmlReportPath to paths.html,
            outputs.logPath to paths.log
        )) {
            if (file.exists()) context.emitOutput(output, file.relativeTo(paths.projectDir).path)
        }

        val failureSummary = DartTestReportParser.formatFailureSummary(summary)
        if (failureSummary.isNotBlank()) {
            context.emitOutput(outputs.failureSummary, failureSummary)
        }

        var belowFloor: String? = null
        if (options.coverage) {
            if (coverage != null) {
                context.emitOutput(outputs.coveragePercent, coverage.coveragePercent)
                val floor = options.minCoverage
                if (floor != null && coverage.coveragePercent < floor) {
                    belowFloor = "Coverage ${coverage.coveragePercent}% is below the ${floor}% floor " +
                        "for '${options.taskName}'."
                }
            }
            if (paths.lcov.exists()) {
                context.emitOutput(outputs.coverageLcov, paths.lcov.relativeTo(paths.projectDir).path)
            }
        }

        val failed = !summary.success || processFailure != null || summary.failedCount > 0
        if ((failed || belowFloor != null) && !options.ignoreFailures) {
            val message = when {
                summary.failedCount > 0 && failed ->
                    "Test task '${options.taskName}' failed: ${summary.failedCount} of " +
                        "${summary.totalCount} tests failed."
                failed -> processFailure?.error?.message ?: "Test task '${options.taskName}' failed."
                else -> belowFloor!!
            }
            return ExecutionResult.Failure(RuntimeException(message), processFailure?.exitCode ?: 1, outputs)
        }

        return ExecutionResult.Success(outputs)
    }
}
