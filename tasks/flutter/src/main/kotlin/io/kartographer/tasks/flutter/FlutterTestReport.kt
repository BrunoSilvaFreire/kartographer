package io.kartographer.tasks.flutter

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class FlutterTestError(
    val error: String,
    val stackTrace: String,
    val isFailure: Boolean = true
)

data class FlutterTestCaseResult(
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
    val errors: List<FlutterTestError> = emptyList()
)

data class FlutterTestRunSummary(
    val totalCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val success: Boolean,
    val testCases: List<FlutterTestCaseResult>
)

data class LcovCoverageSummary(
    val linesFound: Int,
    val linesHit: Int,
    val coveragePercent: Double
)

object FlutterTestReportParser {

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

    fun parseJsonReport(file: File): FlutterTestRunSummary {
        if (!file.exists() || file.length() == 0L) {
            return FlutterTestRunSummary(
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

    fun parseJsonLines(lines: List<String>): FlutterTestRunSummary {
        val suitesMap = mutableMapOf<Int, String>()
        val testStartMap = mutableMapOf<Int, RawTestStart>()
        val testErrorsMap = mutableMapOf<Int, MutableList<FlutterTestError>>()
        val testCases = mutableListOf<FlutterTestCaseResult>()

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
                        .add(FlutterTestError(error = errorMsg, stackTrace = stackTrace, isFailure = isFailure))
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
                        FlutterTestCaseResult(
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

        return FlutterTestRunSummary(
            totalCount = totalCount,
            passedCount = passedCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
            durationMs = totalDuration,
            success = overallSuccess,
            testCases = testCases
        )
    }

    fun combineSummaries(summaries: List<FlutterTestRunSummary>): FlutterTestRunSummary {
        if (summaries.isEmpty()) {
            return FlutterTestRunSummary(
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
        return FlutterTestRunSummary(
            totalCount = allTestCases.size,
            passedCount = summaries.sumOf { it.passedCount },
            failedCount = summaries.sumOf { it.failedCount },
            skippedCount = summaries.sumOf { it.skippedCount },
            durationMs = summaries.sumOf { it.durationMs },
            success = summaries.all { it.success },
            testCases = allTestCases
        )
    }

    fun generateJUnitXml(summary: FlutterTestRunSummary, suiteName: String = "flutter.tests", outputFile: File) {
        outputFile.parentFile?.mkdirs()
        val durationSec = summary.durationMs / 1000.0

        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<testsuite name=\"${escapeXml(suiteName)}\" " +
                    "tests=\"${summary.totalCount}\" " +
                    "failures=\"${summary.failedCount}\" " +
                    "errors=\"0\" " +
                    "skipped=\"${summary.skippedCount}\" " +
                    "time=\"${String.format(java.util.Locale.US, "%.3f", durationSec)}\">\n"
            )

            for (testCase in summary.testCases) {
                val testDurationSec = testCase.durationMs / 1000.0
                val className = testCase.suitePath ?: "FlutterTest"
                append("  <testcase name=\"${escapeXml(testCase.name)}\" classname=\"${escapeXml(className)}\" time=\"${String.format(java.util.Locale.US, "%.3f", testDurationSec)}\"")

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

            append("</testsuite>\n")
        }

        outputFile.writeText(xml)
    }

    fun formatFailureSummary(summary: FlutterTestRunSummary): String {
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

        var totalFound = 0
        var totalHit = 0

        lcovFile.forEachLine { line ->
            when {
                line.startsWith("LF:") -> {
                    totalFound += line.removePrefix("LF:").trim().toIntOrNull() ?: 0
                }
                line.startsWith("LH:") -> {
                    totalHit += line.removePrefix("LH:").trim().toIntOrNull() ?: 0
                }
            }
        }

        val percent = if (totalFound > 0) {
            (totalHit.toDouble() / totalFound) * 100.0
        } else 0.0

        return LcovCoverageSummary(
            linesFound = totalFound,
            linesHit = totalHit,
            coveragePercent = Math.round(percent * 100.0) / 100.0
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
