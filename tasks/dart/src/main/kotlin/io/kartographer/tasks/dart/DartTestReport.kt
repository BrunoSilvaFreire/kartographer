package io.kartographer.tasks.dart

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class DartTestError(
    val error: String,
    val stackTrace: String,
    val isFailure: Boolean = true
)

data class DartTestCaseResult(
    val id: Int,
    val name: String,
    val suitePath: String?,
    val line: Int?,
    val column: Int?,
    val url: String?,
    val skipped: Boolean,
    val skipReason: String?,
    val result: String, // "success", "failure", "error"
    val durationMs: Long,
    val errors: List<DartTestError> = emptyList()
)

data class DartTestRunSummary(
    val totalCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val success: Boolean,
    val testCases: List<DartTestCaseResult>
)

data class LcovFileCoverage(
    val path: String,
    val linesFound: Int,
    val linesHit: Int,
    val coveragePercent: Double,
    val lineHits: Map<Int, Int> = emptyMap(),
    val uncoveredLines: List<Int> = emptyList()
)

data class LcovCoverageSummary(
    val linesFound: Int,
    val linesHit: Int,
    val coveragePercent: Double,
    val files: List<LcovFileCoverage> = emptyList()
) {
    fun sortedBy(selector: (LcovFileCoverage) -> Comparable<*>?): LcovCoverageSummary =
        copy(files = files.sortedWith(compareBy(selector)))

    fun sortedWith(comparator: Comparator<LcovFileCoverage>): LcovCoverageSummary =
        copy(files = files.sortedWith(comparator))

    fun sortedByCoverage(ascending: Boolean = true): LcovCoverageSummary =
        if (ascending) sortedWith(compareBy({ it.coveragePercent }, { it.path }))
        else sortedWith(compareByDescending<LcovFileCoverage> { it.coveragePercent }.thenBy { it.path })

    fun sortedByPath(): LcovCoverageSummary =
        sortedWith(compareBy { it.path })

    fun sortedByMissed(descending: Boolean = true): LcovCoverageSummary =
        if (descending) sortedWith(compareByDescending<LcovFileCoverage> { it.linesFound - it.linesHit }.thenBy { it.path })
        else sortedWith(compareBy<LcovFileCoverage> { it.linesFound - it.linesHit }.thenBy { it.path })
}

object DartTestReportParser {

    object CoverageSort {
        val BY_PATH: Comparator<LcovFileCoverage> = compareBy { it.path }
        val BY_COVERAGE_ASC: Comparator<LcovFileCoverage> = compareBy({ it.coveragePercent }, { it.path })
        val BY_COVERAGE_DESC: Comparator<LcovFileCoverage> = compareByDescending<LcovFileCoverage> { it.coveragePercent }.thenBy { it.path }
        val BY_MISSED_DESC: Comparator<LcovFileCoverage> = compareByDescending<LcovFileCoverage> { it.linesFound - it.linesHit }.thenBy { it.path }
        val BY_LINES_DESC: Comparator<LcovFileCoverage> = compareByDescending<LcovFileCoverage> { it.linesFound }.thenBy { it.path }
    }

    private data class RawTestStart(
        val id: Int,
        val name: String,
        val suiteId: Int?,
        val line: Int?,
        val column: Int?,
        val url: String?,
        val skipped: Boolean,
        val skipReason: String?,
        val startTime: Long
    )

    fun parseJsonReport(file: File): DartTestRunSummary {
        if (!file.exists() || file.length() == 0L) {
            return DartTestRunSummary(
                totalCount = 0,
                passedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                durationMs = 0L,
                success = false,
                testCases = emptyList()
            )
        }
        return parseJsonLines(file.readLines())
    }

    fun parseJsonLines(lines: List<String>): DartTestRunSummary {
        val suitesMap = mutableMapOf<Int, String>()
        val testStartMap = mutableMapOf<Int, RawTestStart>()
        val testErrorsMap = mutableMapOf<Int, MutableList<DartTestError>>()
        val testCases = mutableListOf<DartTestCaseResult>()

        var runnerSuccess: Boolean? = null
        var runnerTotalTime = 0L

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val element = runCatching {
                Json.parseToJsonElement(trimmed).jsonObject
            }.getOrNull() ?: continue

            val type = element["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val time = element["time"]?.jsonPrimitive?.longOrNull ?: 0L

            when (type) {
                "suite" -> {
                    val suiteObj = element["suite"]?.jsonObject ?: continue
                    val suiteId = suiteObj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val path = suiteObj["path"]?.jsonPrimitive?.contentOrNull
                    if (path != null) {
                        suitesMap[suiteId] = path
                    }
                }
                "testStart" -> {
                    val testObj = element["test"]?.jsonObject ?: continue
                    val id = testObj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val name = testObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val suiteId = testObj["suiteID"]?.jsonPrimitive?.intOrNull
                    val metadata = testObj["metadata"]?.jsonObject
                    val skipped = metadata?.get("skip")?.jsonPrimitive?.booleanOrNull ?: false
                    val skipReason = metadata?.get("skipReason")?.jsonPrimitive?.contentOrNull

                    val rootLine = testObj["root_line"]?.jsonPrimitive?.intOrNull
                        ?: testObj["line"]?.jsonPrimitive?.intOrNull
                    val rootColumn = testObj["root_column"]?.jsonPrimitive?.intOrNull
                        ?: testObj["column"]?.jsonPrimitive?.intOrNull
                    val rootUrl = testObj["root_url"]?.jsonPrimitive?.contentOrNull
                        ?: testObj["url"]?.jsonPrimitive?.contentOrNull

                    testStartMap[id] = RawTestStart(
                        id = id,
                        name = name,
                        suiteId = suiteId,
                        line = rootLine,
                        column = rootColumn,
                        url = rootUrl,
                        skipped = skipped,
                        skipReason = skipReason,
                        startTime = time
                    )
                }
                "error" -> {
                    val testId = element["testID"]?.jsonPrimitive?.intOrNull ?: continue
                    val errorMsg = element["error"]?.jsonPrimitive?.contentOrNull ?: ""
                    val stackTrace = element["stackTrace"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isFailure = element["isFailure"]?.jsonPrimitive?.booleanOrNull ?: true

                    testErrorsMap.computeIfAbsent(testId) { mutableListOf() }
                        .add(DartTestError(error = errorMsg, stackTrace = stackTrace, isFailure = isFailure))
                }
                "testDone" -> {
                    val testId = element["testID"]?.jsonPrimitive?.intOrNull ?: continue
                    val hidden = element["hidden"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (hidden) continue

                    val result = element["result"]?.jsonPrimitive?.contentOrNull ?: "failure"
                    val skipped = element["skipped"]?.jsonPrimitive?.booleanOrNull ?: false
                    val startInfo = testStartMap[testId]

                    val duration = if (startInfo != null) maxOf(0L, time - startInfo.startTime) else 0L
                    val errors = testErrorsMap[testId] ?: emptyList()
                    val suitePath = startInfo?.suiteId?.let { suitesMap[it] }

                    testCases.add(
                        DartTestCaseResult(
                            id = testId,
                            name = startInfo?.name ?: "test-$testId",
                            suitePath = suitePath,
                            line = startInfo?.line,
                            column = startInfo?.column,
                            url = startInfo?.url,
                            skipped = skipped || (startInfo?.skipped == true),
                            skipReason = startInfo?.skipReason,
                            result = result,
                            durationMs = duration,
                            errors = errors
                        )
                    )
                }
                "done" -> {
                    runnerSuccess = element["success"]?.jsonPrimitive?.booleanOrNull
                    runnerTotalTime = time
                }
            }
        }

        val totalCount = testCases.size
        val passedCount = testCases.count { it.result == "success" && !it.skipped }
        val failedCount = testCases.count { (it.result == "failure" || it.result == "error") && !it.skipped }
        val skippedCount = testCases.count { it.skipped }
        val overallSuccess = (runnerSuccess ?: (failedCount == 0)) && failedCount == 0

        val totalDuration = if (runnerTotalTime > 0L) runnerTotalTime else testCases.sumOf { it.durationMs }

        return DartTestRunSummary(
            totalCount = totalCount,
            passedCount = passedCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
            durationMs = totalDuration,
            success = overallSuccess,
            testCases = testCases
        )
    }

    fun combineSummaries(summaries: List<DartTestRunSummary>): DartTestRunSummary {
        if (summaries.isEmpty()) {
            return DartTestRunSummary(
                totalCount = 0,
                passedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                durationMs = 0L,
                success = true,
                testCases = emptyList()
            )
        }
        val allTestCases = summaries.flatMap { it.testCases }
        return DartTestRunSummary(
            totalCount = allTestCases.size,
            passedCount = summaries.sumOf { it.passedCount },
            failedCount = summaries.sumOf { it.failedCount },
            skippedCount = summaries.sumOf { it.skippedCount },
            durationMs = summaries.sumOf { it.durationMs },
            success = summaries.all { it.success },
            testCases = allTestCases
        )
    }

    fun generateJUnitXml(summary: DartTestRunSummary, suiteName: String = "tests", outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + testSuiteElement(suiteName, summary) + "\n"
        )
    }

    /** One `<testsuite>` element — shared by the per-task report and the merged one. */
    private fun testSuiteElement(suiteName: String, summary: DartTestRunSummary): String = buildString {
        append(
            "<testsuite name=\"${escapeXml(suiteName)}\" " +
                "tests=\"${summary.totalCount}\" " +
                "failures=\"${summary.failedCount}\" " +
                "errors=\"0\" " +
                "skipped=\"${summary.skippedCount}\" " +
                "time=\"${seconds(summary.durationMs)}\">\n"
        )

        for (testCase in summary.testCases) {
            val className = testCase.suitePath ?: suiteName
            append("  <testcase name=\"${escapeXml(testCase.name)}\" classname=\"${escapeXml(className)}\" time=\"${seconds(testCase.durationMs)}\"")

            if (testCase.skipped) {
                append(">\n")
                val reason = testCase.skipReason ?: "Skipped"
                append("    <skipped message=\"${escapeXml(reason)}\"/>\n")
                append("  </testcase>\n")
            } else if (testCase.result == "failure" || testCase.result == "error") {
                append(">\n")
                val primaryError = testCase.errors.firstOrNull()
                val errorMessage = primaryError?.error?.lineSequence()?.firstOrNull() ?: "Test failed"
                val fullTrace = buildString {
                    for (err in testCase.errors) {
                        append(err.error)
                        if (err.stackTrace.isNotBlank()) {
                            append("\n").append(err.stackTrace)
                        }
                    }
                }.trim()

                append("    <failure message=\"${escapeXml(errorMessage)}\">")
                append("<![CDATA[${safeCdata(fullTrace)}]]>")
                append("</failure>\n")
                append("  </testcase>\n")
            } else {
                append("/>\n")
            }
        }

        append("</testsuite>")
    }

    /**
     * One suite in a report: a name, its results, and optionally its coverage.
     *
     * A per-task report has one of these; the run's aggregate has one per task.
     */
    data class SuiteReport(
        val name: String,
        val summary: DartTestRunSummary,
        val coveragePercent: Double? = null,
        val coverage: LcovCoverageSummary? = null
    ) {
        val effectiveCoveragePercent: Double?
            get() = coverage?.coveragePercent ?: coveragePercent
    }

    /** `<testsuites>` wrapping one `<testsuite>` per suite, for a merged report. */
    fun generateJUnitXml(suites: List<SuiteReport>, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(
            buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<testsuites")
                append(" tests=\"${suites.sumOf { it.summary.totalCount }}\"")
                append(" failures=\"${suites.sumOf { it.summary.failedCount }}\"")
                append(" skipped=\"${suites.sumOf { it.summary.skippedCount }}\"")
                append(" time=\"${seconds(suites.sumOf { it.summary.durationMs })}\">\n")
                for (suite in suites) {
                    append(testSuiteElement(suite.name, suite.summary).prependIndent("  "))
                    append("\n")
                }
                append("</testsuites>\n")
            }
        )
    }

    /**
     * A self-contained HTML report.
     *
     * Written here rather than handed to `npx xunit-viewer`, which meant every test run
     * downloaded a package from npm to re-parse the XML we had just serialised — and then
     * rendered an empty page anyway. The summary in hand is richer than the XML, so the
     * report is built straight from it: no network, no JavaScript, no external assets.
     */
    fun generateHtmlReport(
        suites: List<SuiteReport>,
        htmlFile: File,
        title: String,
        coverage: LcovCoverageSummary? = null,
        coverageComparator: Comparator<LcovFileCoverage>? = null
    ): Boolean {
        htmlFile.parentFile?.mkdirs()

        val total = suites.sumOf { it.summary.totalCount }
        val passed = suites.sumOf { it.summary.passedCount }
        val failed = suites.sumOf { it.summary.failedCount }
        val skipped = suites.sumOf { it.summary.skippedCount }
        val durationMs = suites.sumOf { it.summary.durationMs }

        val suiteCoverages = suites.mapNotNull { it.coverage }
        val resolvedCoverage = coverage ?: if (suiteCoverages.isNotEmpty()) {
            val allFiles = suiteCoverages.flatMap { it.files }
            val totalFound = allFiles.sumOf { it.linesFound }
            val totalHit = allFiles.sumOf { it.linesHit }
            val pct = if (totalFound > 0) Math.round((totalHit.toDouble() / totalFound) * 10000.0) / 100.0 else 0.0
            LcovCoverageSummary(linesFound = totalFound, linesHit = totalHit, coveragePercent = pct, files = allFiles)
        } else null

        val covered = if (resolvedCoverage != null) {
            listOf(resolvedCoverage.coveragePercent)
        } else {
            suites.mapNotNull { it.effectiveCoveragePercent }
        }

        htmlFile.writeText(
            buildString {
                append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                append("<meta charset=\"utf-8\">\n")
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                append("<title>${escapeXml(title)}</title>\n")
                append("<style>\n$STYLE\n</style>\n")
                append("</head>\n<body>\n")

                append("<header>\n")
                append("<h1>${escapeXml(title)}</h1>\n")
                append("<div class=\"totals\">\n")
                stat(this, "tests", total.toString(), null)
                stat(this, "passed", passed.toString(), if (passed > 0) "pass" else null)
                stat(this, "failed", failed.toString(), if (failed > 0) "fail" else null)
                if (skipped > 0) stat(this, "skipped", skipped.toString(), "skip")
                stat(this, "suites", suites.size.toString(), null)
                stat(this, "duration", formatDuration(durationMs), null)
                if (resolvedCoverage != null) {
                    val covState = if (resolvedCoverage.coveragePercent >= 80.0) "pass" else if (resolvedCoverage.coveragePercent >= 50.0) "skip" else "fail"
                    stat(this, "coverage", "${resolvedCoverage.coveragePercent}%", covState)
                    stat(this, "lines", "${resolvedCoverage.linesHit}/${resolvedCoverage.linesFound}", null)
                } else if (covered.isNotEmpty()) {
                    val mean = covered.sum() / covered.size
                    stat(this, "coverage", "${Math.round(mean * 100.0) / 100.0}%", null)
                }
                append("</div>\n</header>\n")

                if (total == 0 && suites.isEmpty()) {
                    append("<p class=\"empty\">No test results.</p>\n")
                }

                for (suite in suites.sortedWith(compareBy({ it.summary.failedCount == 0 }, { it.name }))) {
                    val s = suite.summary
                    // Suites with failures start open: that is what the reader came for.
                    val open = if (s.failedCount > 0) " open" else ""
                    append("<details class=\"suite\"$open>\n<summary>")
                    append("<span class=\"badge ${if (s.failedCount > 0) "fail" else "pass"}\">")
                    append(if (s.failedCount > 0) "FAILED" else "ok")
                    append("</span> ")
                    append("<span class=\"name\">${escapeXml(suite.name)}</span> ")
                    append("<span class=\"meta\">${s.passedCount}/${s.totalCount} passed")
                    if (s.failedCount > 0) append(", ${s.failedCount} failed")
                    if (s.skippedCount > 0) append(", ${s.skippedCount} skipped")
                    append(" &middot; ${formatDuration(s.durationMs)}")
                    suite.effectiveCoveragePercent?.let { append(" &middot; ${it}% covered") }
                    append("</span></summary>\n")

                    if (s.testCases.isEmpty()) {
                        append("<p class=\"empty\">This task reported counts but no individual tests.</p>\n")
                    } else {
                        append("<table>\n<thead><tr><th>Test</th><th>File</th><th class=\"num\">Time</th></tr></thead>\n<tbody>\n")
                        for (case in s.testCases.sortedWith(compareBy({ it.result != "failure" && it.result != "error" }, { it.name }))) {
                            val state = when {
                                case.result == "failure" || case.result == "error" -> "fail"
                                case.skipped -> "skip"
                                else -> "pass"
                            }
                            append("<tr class=\"$state\">")
                            append("<td><span class=\"dot\"></span>${escapeXml(case.name)}")
                            if (state == "fail" && case.errors.isNotEmpty()) {
                                append("<details class=\"error\"><summary>error</summary><pre>")
                                for (error in case.errors) {
                                    append(escapeXml(error.error.trim()))
                                    if (error.stackTrace.isNotBlank()) {
                                        append("\n")
                                        append(escapeXml(error.stackTrace.trim()))
                                    }
                                    append("\n")
                                }
                                append("</pre></details>")
                            }
                            append("</td>")
                            val location = buildString {
                                case.suitePath?.let { append(it) }
                                case.line?.let { append(":").append(it) }
                            }
                            append("<td class=\"file\">${escapeXml(location)}</td>")
                            append("<td class=\"num\">${formatDuration(case.durationMs)}</td>")
                            append("</tr>\n")
                        }
                        append("</tbody>\n</table>\n")
                    }
                    append("</details>\n")
                }

                if (resolvedCoverage != null) {
                    renderCoverageSection(this, resolvedCoverage, htmlFile, coverageComparator)
                    append("<script>\n$SCRIPT\n</script>\n")
                }

                append("</body>\n</html>\n")
            }
        )
        return htmlFile.exists()
    }

    private fun stat(target: StringBuilder, label: String, value: String, state: String?) {
        target.append("<div class=\"stat")
        if (state != null) target.append(" ").append(state)
        target.append("\"><span class=\"value\">").append(escapeXml(value))
        target.append("</span><span class=\"label\">").append(escapeXml(label)).append("</span></div>\n")
    }

    private fun formatDuration(ms: Long): String = when {
        ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
        ms >= 1_000 -> "${String.format(java.util.Locale.US, "%.2f", ms / 1000.0)}s"
        else -> "${ms}ms"
    }

    private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.3f", ms / 1000.0)

    private val STYLE = """
:root {
  color-scheme: light dark;
  --bg: #ffffff; --fg: #1a1a1a; --muted: #6b7280; --line: #e5e7eb; --panel: #f9fafb;
  --pass: #15803d; --fail: #b91c1c; --skip: #a16207;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #14171c; --fg: #e6e8eb; --muted: #9aa3af; --line: #2a2f37; --panel: #1a1e24;
    --pass: #4ade80; --fail: #f87171; --skip: #fbbf24;
  }
}
* { box-sizing: border-box; }
body { margin: 0; padding: 24px; background: var(--bg); color: var(--fg);
  font: 14px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
h1 { margin: 0 0 16px; font-size: 20px; font-weight: 600; }
header { max-width: 1100px; margin: 0 auto 24px; }
.totals { display: flex; flex-wrap: wrap; gap: 8px; }
.stat { border: 1px solid var(--line); border-radius: 8px; padding: 8px 14px; background: var(--panel); min-width: 84px; }
.stat .value { display: block; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; }
.stat .label { display: block; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
.stat.pass .value { color: var(--pass); }
.stat.fail .value { color: var(--fail); }
.stat.skip .value { color: var(--skip); }
.suite { max-width: 1100px; margin: 0 auto 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); }
.suite > summary { cursor: pointer; padding: 10px 14px; display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.suite > summary::marker { color: var(--muted); }
.badge { font-size: 11px; font-weight: 700; letter-spacing: .04em; padding: 2px 7px; border-radius: 4px; }
.badge.pass { color: var(--pass); border: 1px solid var(--pass); }
.badge.fail { color: var(--fail); border: 1px solid var(--fail); }
.name { font-weight: 600; }
.meta { color: var(--muted); font-size: 12px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
thead th { text-align: left; padding: 6px 14px; border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line); font-size: 11px; text-transform: uppercase;
  letter-spacing: .06em; color: var(--muted); font-weight: 600; }
tbody td { padding: 5px 14px; border-bottom: 1px solid var(--line); vertical-align: top; }
tbody tr:last-child td { border-bottom: none; }
.num { text-align: right; font-variant-numeric: tabular-nums; color: var(--muted); white-space: nowrap; }
.file { color: var(--muted); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; word-break: break-all; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 8px; vertical-align: middle; }
tr.pass .dot { background: var(--pass); }
tr.fail .dot { background: var(--fail); }
tr.skip .dot { background: var(--skip); }
tr.fail td:first-child { color: var(--fail); }
details.error { margin: 6px 0 2px; }
details.error > summary { cursor: pointer; font-size: 11px; text-transform: uppercase;
  letter-spacing: .06em; color: var(--muted); }
details.error pre { margin: 6px 0 0; padding: 10px; overflow-x: auto; background: var(--bg);
  border: 1px solid var(--line); border-radius: 6px; color: var(--fg);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; white-space: pre; }
.empty { max-width: 1100px; margin: 0 auto; padding: 10px 14px; color: var(--muted); }
.coverage-bar-container { display: flex; align-items: center; gap: 12px; padding: 10px 14px; border-bottom: 1px solid var(--line); }
.coverage-bar { flex: 1; height: 10px; background: var(--line); border-radius: 5px; overflow: hidden; }
.coverage-bar-fill { height: 100%; background: var(--pass); border-radius: 5px; }
.coverage-bar-fill.warn { background: var(--skip); }
.coverage-bar-fill.bad { background: var(--fail); }
.coverage-bar-label { font-size: 12px; font-weight: 600; color: var(--muted); white-space: nowrap; font-variant-numeric: tabular-nums; }
.mini-bar-container { display: inline-flex; align-items: center; gap: 8px; }
.mini-bar { width: 50px; height: 6px; background: var(--line); border-radius: 3px; overflow: hidden; }
.mini-bar-fill { height: 100%; background: var(--pass); border-radius: 3px; }
.mini-bar-fill.warn { background: var(--skip); }
.mini-bar-fill.bad { background: var(--fail); }
.cov-file-link { color: inherit; text-decoration: none; border-bottom: 1px dashed var(--muted); }
.cov-file-link:hover { color: var(--pass); border-bottom-color: var(--pass); }
.uncovered { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--fail); }
.coverage-files-list { padding: 10px 14px; }
.section-subtitle { margin: 12px 0 8px; font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); }
.coverage-file { margin: 8px 0; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); overflow: hidden; }
.coverage-file > summary { cursor: pointer; padding: 8px 12px; background: var(--panel); display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.coverage-file > summary::marker { color: var(--muted); }
.source-view { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.45; overflow-x: auto; border-top: 1px solid var(--line); background: var(--bg); }
.code-line { display: flex; min-width: fit-content; }
.code-line .ln { width: 50px; padding: 0 8px; text-align: right; color: var(--muted); user-select: none; border-right: 1px solid var(--line); background: var(--panel); flex-shrink: 0; }
.code-line .hits { width: 44px; padding: 0 6px; text-align: right; font-size: 11px; user-select: none; color: var(--muted); border-right: 1px solid var(--line); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.code-line .code { padding: 0 12px; white-space: pre; flex: 1; }
.code-line.cov-hit { background: rgba(21, 128, 61, 0.10); }
.code-line.cov-hit .hits { color: var(--pass); font-weight: 600; }
.code-line.cov-miss { background: rgba(185, 28, 28, 0.12); }
.code-line.cov-miss .hits { color: var(--fail); font-weight: 600; }
.code-line.cov-none .hits { opacity: 0.25; }
@media (prefers-color-scheme: dark) {
  .code-line.cov-hit { background: rgba(74, 222, 128, 0.12); }
  .code-line.cov-miss { background: rgba(248, 113, 113, 0.16); }
}
th.sortable { cursor: pointer; user-select: none; white-space: nowrap; }
th.sortable:hover { color: var(--fg); background: var(--panel); }
.sort-btn { display: inline-flex; align-items: center; justify-content: center; background: transparent;
  border: 1px solid transparent; border-radius: 4px; padding: 1px 3px; margin-left: 4px; cursor: pointer;
  color: var(--muted); font-size: 10px; line-height: 1; vertical-align: middle; }
th.sortable:hover .sort-btn { color: var(--fg); border-color: var(--line); background: var(--bg); }
th.sortable[data-sort] { color: var(--fg); }
th.sortable[data-sort] .sort-btn { color: var(--pass); border-color: var(--pass); background: var(--bg); font-weight: 700; }
.sort-btn:focus-visible { outline: 2px solid var(--pass); outline-offset: 1px; }
""".trimIndent()

    private val SCRIPT = """
function sortCoverageTable(target, col) {
  var th = target.closest ? target.closest('th') : target;
  if (!th) return;
  var table = th.closest('table');
  if (!table) return;
  var tbody = table.querySelector('tbody');
  if (!tbody) return;
  var rows = Array.from(tbody.querySelectorAll('tr'));
  var currentSort = th.getAttribute('data-sort');
  var newSort;
  if (currentSort === 'asc') {
    newSort = 'desc';
  } else if (currentSort === 'desc') {
    newSort = 'asc';
  } else {
    newSort = (col === 'file' || col === 'cov') ? 'asc' : 'desc';
  }

  table.querySelectorAll('th.sortable').forEach(function(h) {
    h.removeAttribute('data-sort');
    var icon = h.querySelector('.sort-icon');
    if (icon) icon.textContent = '↕';
  });

  th.setAttribute('data-sort', newSort);
  var icon = th.querySelector('.sort-icon');
  if (icon) icon.textContent = newSort === 'asc' ? '▲' : '▼';

  var isNum = col !== 'file';
  rows.sort(function(a, b) {
    var valA = a.getAttribute('data-' + col) || '';
    var valB = b.getAttribute('data-' + col) || '';
    if (isNum) {
      var nA = parseFloat(valA) || 0;
      var nB = parseFloat(valB) || 0;
      if (nA !== nB) {
        return newSort === 'asc' ? nA - nB : nB - nA;
      }
      var fA = a.getAttribute('data-file') || '';
      var fB = b.getAttribute('data-file') || '';
      return fA.localeCompare(fB);
    }
    return newSort === 'asc' ? valA.localeCompare(valB) : valB.localeCompare(valA);
  });

  rows.forEach(function(r) { tbody.appendChild(r); });

  var container = table.closest('.coverage');
  if (container) {
    var list = container.querySelector('.coverage-files-list');
    if (list) {
      rows.forEach(function(r) {
        var idx = r.getAttribute('data-idx');
        if (idx !== null) {
          var card = list.querySelector('#file-' + idx);
          if (card) list.appendChild(card);
        }
      });
    }
  }
}
""".trimIndent()

    private fun renderCoverageSection(
        target: StringBuilder,
        coverage: LcovCoverageSummary,
        htmlFile: File,
        coverageComparator: Comparator<LcovFileCoverage>? = null
    ) {
        val totalFound = coverage.linesFound
        val totalHit = coverage.linesHit
        val pct = coverage.coveragePercent
        val badgeClass = if (pct >= 80.0) "pass" else if (pct >= 50.0) "skip" else "fail"
        val openAttr = if (pct < 100.0) " open" else ""

        target.append("<details class=\"suite coverage\"$openAttr>\n<summary>")
        target.append("<span class=\"badge $badgeClass\">COVERAGE</span> ")
        target.append("<span class=\"name\">Coverage Report</span> ")
        target.append("<span class=\"meta\">$totalHit/$totalFound lines hit &middot; $pct% covered</span>")
        target.append("</summary>\n")

        val barColorClass = if (pct >= 80.0) "" else if (pct >= 50.0) " warn" else " bad"
        target.append("<div class=\"coverage-bar-container\">\n")
        target.append("  <div class=\"coverage-bar\"><div class=\"coverage-bar-fill$barColorClass\" style=\"width: ${pct}%\"></div></div>\n")
        target.append("  <span class=\"coverage-bar-label\">$totalHit / $totalFound lines ($pct%)</span>\n")
        target.append("</div>\n")

        if (coverage.files.isNotEmpty()) {
            val sortedFiles = if (coverageComparator != null) {
                coverage.files.sortedWith(coverageComparator)
            } else {
                coverage.files
            }

            val initialCol = when (coverageComparator) {
                CoverageSort.BY_PATH -> "file" to "asc"
                CoverageSort.BY_COVERAGE_ASC -> "cov" to "asc"
                CoverageSort.BY_COVERAGE_DESC -> "cov" to "desc"
                CoverageSort.BY_MISSED_DESC -> "miss" to "desc"
                CoverageSort.BY_LINES_DESC -> "lines" to "desc"
                else -> null
            }

            fun renderHeader(col: String, label: String, extraClasses: String = "", extraStyle: String = ""): String {
                val isSorted = initialCol?.first == col
                val sortDir = if (isSorted) initialCol!!.second else null
                val sortAttr = if (sortDir != null) " data-sort=\"$sortDir\"" else ""
                val sortIcon = when (sortDir) {
                    "asc" -> "▲"
                    "desc" -> "▼"
                    else -> "↕"
                }
                val classes = ("sortable $extraClasses").trim()
                val classAttr = " class=\"$classes\""
                val styleAttr = if (extraStyle.isNotBlank()) " style=\"$extraStyle\"" else ""
                return "<th$classAttr$styleAttr data-col=\"$col\"$sortAttr onclick=\"sortCoverageTable(this, '$col')\">$label <button type=\"button\" class=\"sort-btn\" title=\"Sort by $label\" aria-label=\"Sort by $label\"><span class=\"sort-icon\">$sortIcon</span></button></th>"
            }

            target.append("<table class=\"coverage-table\">\n")
            target.append("<thead><tr>\n")
            target.append("  ${renderHeader("file", "File")}\n")
            target.append("  ${renderHeader("cov", "Coverage", extraStyle = "min-width: 140px;")}\n")
            target.append("  ${renderHeader("lines", "Lines", extraClasses = "num")}\n")
            target.append("  ${renderHeader("hit", "Hit", extraClasses = "num")}\n")
            target.append("  ${renderHeader("miss", "Miss", extraClasses = "num")}\n")
            target.append("  ${renderHeader("uncovered", "Uncovered")}\n")
            target.append("</tr></thead>\n")
            target.append("<tbody>\n")
            for ((idx, file) in sortedFiles.withIndex()) {
                val fileBadge = if (file.coveragePercent >= 80.0) "pass" else if (file.coveragePercent >= 50.0) "skip" else "fail"
                val fileBarColor = if (file.coveragePercent >= 80.0) "" else if (file.coveragePercent >= 50.0) " warn" else " bad"
                val missed = file.linesFound - file.linesHit
                val anchorId = "file-$idx"
                target.append("<tr data-idx=\"$idx\" data-file=\"${escapeXml(file.path)}\" data-cov=\"${file.coveragePercent}\" data-lines=\"${file.linesFound}\" data-hit=\"${file.linesHit}\" data-miss=\"$missed\" data-uncovered=\"${file.uncoveredLines.size}\">\n")
                target.append("<td class=\"file\"><a href=\"#$anchorId\" class=\"cov-file-link\">${escapeXml(file.path)}</a></td>\n")
                target.append("<td><div class=\"mini-bar-container\"><span class=\"badge $fileBadge\">${file.coveragePercent}%</span><div class=\"mini-bar\"><div class=\"mini-bar-fill$fileBarColor\" style=\"width: ${file.coveragePercent}%\"></div></div></div></td>\n")
                target.append("<td class=\"num\">${file.linesFound}</td>\n")
                target.append("<td class=\"num\">${file.linesHit}</td>\n")
                target.append("<td class=\"num\">$missed</td>\n")
                val uncovText = formatLineRanges(file.uncoveredLines)
                target.append("<td class=\"uncovered\">${if (uncovText.isNotEmpty()) escapeXml(uncovText) else "&mdash;"}</td>\n")
                target.append("</tr>\n")
            }
            target.append("</tbody>\n</table>\n")

            target.append("<div class=\"coverage-files-list\">\n")
            target.append("<h3 class=\"section-subtitle\">Source &amp; Line Visualization</h3>\n")
            for ((idx, file) in sortedFiles.withIndex()) {
                renderFileCoverage(target, file, htmlFile, idx)
            }
            target.append("</div>\n")
        } else {
            target.append("<p class=\"empty\">No file coverage details recorded.</p>\n")
        }

        target.append("</details>\n")
    }

    private fun renderFileCoverage(
        target: StringBuilder,
        file: LcovFileCoverage,
        htmlFile: File,
        index: Int
    ) {
        val sourceFile = resolveSourceFile(file.path, htmlFile)
        val anchorId = "file-$index"
        val openAttr = if (file.coveragePercent < 100.0) " open" else ""

        target.append("<details class=\"coverage-file\" id=\"$anchorId\"$openAttr>\n")
        target.append("<summary>")
        val badgeState = when {
            file.coveragePercent >= 80.0 -> "pass"
            file.coveragePercent >= 50.0 -> "skip"
            else -> "fail"
        }
        target.append("<span class=\"badge $badgeState\">${file.coveragePercent}%</span> ")
        target.append("<span class=\"name\">${escapeXml(file.path)}</span> ")
        target.append("<span class=\"meta\">${file.linesHit}/${file.linesFound} lines")
        if (file.uncoveredLines.isNotEmpty()) {
            target.append(" &middot; Missed: ${formatLineRanges(file.uncoveredLines)}")
        }
        target.append("</span></summary>\n")

        if (sourceFile != null) {
            val lines = runCatching { sourceFile.readLines() }.getOrNull()
            if (lines != null) {
                target.append("<div class=\"source-view\">\n")
                lines.forEachIndexed { idx, lineText ->
                    val lineNum = idx + 1
                    val hitCount = file.lineHits[lineNum]
                    val (lineClass, hitDisplay) = when {
                        hitCount == null -> "cov-none" to ""
                        hitCount > 0 -> "cov-hit" to "${hitCount}x"
                        else -> "cov-miss" to "0x"
                    }
                    target.append("<div class=\"code-line $lineClass\">")
                    target.append("<span class=\"ln\">$lineNum</span>")
                    target.append("<span class=\"hits\">$hitDisplay</span>")
                    target.append("<span class=\"code\">${escapeXml(lineText)}</span>")
                    target.append("</div>\n")
                }
                target.append("</div>\n")
            } else {
                renderLineMapFallback(target, file)
            }
        } else {
            renderLineMapFallback(target, file)
        }

        target.append("</details>\n")
    }

    private fun renderLineMapFallback(target: StringBuilder, file: LcovFileCoverage) {
        if (file.lineHits.isEmpty()) {
            target.append("<p class=\"empty\">No line coverage data available for this file.</p>\n")
            return
        }
        target.append("<div class=\"source-view\">\n")
        val sortedLines = file.lineHits.keys.sorted()
        for (lineNum in sortedLines) {
            val count = file.lineHits[lineNum] ?: 0
            val lineClass = if (count > 0) "cov-hit" else "cov-miss"
            val hitDisplay = if (count > 0) "${count}x" else "0x"
            target.append("<div class=\"code-line $lineClass\">")
            target.append("<span class=\"ln\">$lineNum</span>")
            target.append("<span class=\"hits\">$hitDisplay</span>")
            target.append("<span class=\"code\"><em>Line $lineNum (${if (count > 0) "hit $count times" else "missed"})</em></span>")
            target.append("</div>\n")
        }
        target.append("</div>\n")
    }

    private fun resolveSourceFile(path: String, htmlFile: File): File? {
        val f = File(path)
        if (f.isAbsolute && f.exists() && f.isFile) return f
        val relativeToHtml = File(htmlFile.parentFile, path)
        if (relativeToHtml.exists() && relativeToHtml.isFile) return relativeToHtml
        var dir = htmlFile.parentFile
        repeat(4) {
            if (dir != null) {
                val candidate = File(dir, path)
                if (candidate.exists() && candidate.isFile) return candidate
                dir = dir?.parentFile
            }
        }
        val cwdFile = File(path)
        if (cwdFile.exists() && cwdFile.isFile) return cwdFile
        return null
    }

    fun formatLineRanges(lines: List<Int>): String {
        if (lines.isEmpty()) return ""
        val sorted = lines.distinct().sorted()
        val ranges = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]

        for (i in 1 until sorted.size) {
            val curr = sorted[i]
            if (curr == prev + 1) {
                prev = curr
            } else {
                ranges.add(if (start == prev) "$start" else "$start-$prev")
                start = curr
                prev = curr
            }
        }
        ranges.add(if (start == prev) "$start" else "$start-$prev")
        return ranges.joinToString(", ")
    }

    fun formatFailureSummary(summary: DartTestRunSummary): String {
        val failures = summary.testCases.filter { it.result == "failure" || it.result == "error" }
        if (failures.isEmpty()) return ""

        return buildString {
            append("### Test Failures: ${summary.failedCount} of ${summary.totalCount} failed (${summary.durationMs}ms)\n\n")
            failures.forEachIndexed { index, testCase ->
                append("${index + 1}. **${testCase.name}**\n")
                val loc = buildString {
                    val file = testCase.suitePath ?: testCase.url
                    if (file != null) append(file)
                    if (testCase.line != null) append(":${testCase.line}")
                }
                if (loc.isNotBlank()) {
                    append("   - **Location**: `$loc`\n")
                }

                if (testCase.errors.isNotEmpty()) {
                    val primary = testCase.errors.first()
                    append("   - **Error**:\n     ```\n     ")
                    append(primary.error.trim().replace("\n", "\n     "))
                    append("\n     ```\n")

                    if (primary.stackTrace.isNotBlank()) {
                        val cleanedTrace = primary.stackTrace.lines()
                            .filter { line ->
                                line.contains("package:") || line.contains(".dart:") || line.contains("test/")
                            }
                            .take(6)
                            .joinToString("\n     ")
                        if (cleanedTrace.isNotBlank()) {
                            append("   - **Stack**:\n     ```\n     ")
                            append(cleanedTrace)
                            append("\n     ```\n")
                        }
                    }
                }
                append("\n")
            }
        }.trimEnd()
    }

    fun parseLcovCoverage(lcovFile: File): LcovCoverageSummary? {
        if (!lcovFile.exists() || lcovFile.length() == 0L) return null
        return parseLcovLines(lcovFile.readLines())
    }

    fun parseLcovLines(lines: List<String>): LcovCoverageSummary {
        val files = mutableListOf<LcovFileCoverage>()
        var currentSource: String? = null
        var currentLines = mutableMapOf<Int, Int>()
        var currentLf: Int? = null
        var currentLh: Int? = null

        var totalFound = 0
        var totalHit = 0

        fun flushCurrentRecord() {
            val src = currentSource ?: return
            val lf = currentLf ?: currentLines.size
            val lh = currentLh ?: currentLines.count { it.value > 0 }
            val pct = if (lf > 0) Math.round((lh.toDouble() / lf) * 10000.0) / 100.0 else 0.0
            val uncovered = currentLines.filter { it.value == 0 }.keys.sorted()
            files.add(
                LcovFileCoverage(
                    path = src,
                    linesFound = lf,
                    linesHit = lh,
                    coveragePercent = pct,
                    lineHits = currentLines.toMap(),
                    uncoveredLines = uncovered
                )
            )
            totalFound += lf
            totalHit += lh
            currentSource = null
            currentLines = mutableMapOf()
            currentLf = null
            currentLh = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("SF:") -> {
                    if (currentSource != null) flushCurrentRecord()
                    currentSource = trimmed.removePrefix("SF:").trim()
                    currentLines = mutableMapOf()
                    currentLf = null
                    currentLh = null
                }
                trimmed.startsWith("DA:") -> {
                    val parts = trimmed.removePrefix("DA:").split(",")
                    val lineNum = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    if (lineNum != null) {
                        currentLines[lineNum] = (currentLines[lineNum] ?: 0) + count
                    }
                }
                trimmed.startsWith("LF:") -> {
                    currentLf = trimmed.removePrefix("LF:").trim().toIntOrNull()
                }
                trimmed.startsWith("LH:") -> {
                    currentLh = trimmed.removePrefix("LH:").trim().toIntOrNull()
                }
                trimmed == "end_of_record" -> {
                    flushCurrentRecord()
                }
            }
        }
        if (currentSource != null) {
            flushCurrentRecord()
        }

        val percent = if (totalFound > 0) {
            (totalHit.toDouble() / totalFound) * 100.0
        } else 0.0

        return LcovCoverageSummary(
            linesFound = totalFound,
            linesHit = totalHit,
            coveragePercent = Math.round(percent * 100.0) / 100.0,
            files = files
        )
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun safeCdata(str: String): String {
        return str.replace("]]>", "]]]]><![CDATA[>")
    }
}
