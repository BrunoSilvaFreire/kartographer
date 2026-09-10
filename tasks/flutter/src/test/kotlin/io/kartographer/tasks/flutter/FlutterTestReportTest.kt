package io.kartographer.tasks.flutter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class FlutterTestReportTest {

    @Test
    fun `parses json lines with passing, failing, and skipped tests`() {
        val sampleJsonLines = listOf(
            """{"protocolVersion":"0.1.1","runnerVersion":"1.30.0","pid":100,"type":"start","time":0}""",
            """{"suite":{"id":0,"platform":"vm","path":"test/sample_test.dart"},"type":"suite","time":0}""",
            """{"test":{"id":1,"name":"loading test/sample_test.dart","suiteID":0,"groupIDs":[],"metadata":{"skip":false,"skipReason":null},"line":null,"column":null,"url":null},"type":"testStart","time":0}""",
            """{"testID":1,"result":"success","skipped":false,"hidden":true,"type":"testDone","time":100}""",
            """{"test":{"id":2,"name":"sample test passes","suiteID":0,"groupIDs":[],"metadata":{"skip":false,"skipReason":null},"line":10,"column":5,"url":"file:///test/sample_test.dart"},"type":"testStart","time":105}""",
            """{"testID":2,"result":"success","skipped":false,"hidden":false,"type":"testDone","time":205}""",
            """{"test":{"id":3,"name":"sample test is skipped","suiteID":0,"groupIDs":[],"metadata":{"skip":true,"skipReason":"Not ready"},"line":20,"column":5,"url":"file:///test/sample_test.dart"},"type":"testStart","time":210}""",
            """{"testID":3,"result":"success","skipped":true,"hidden":false,"type":"testDone","time":215}""",
            """{"test":{"id":4,"name":"sample test fails","suiteID":0,"groupIDs":[],"metadata":{"skip":false,"skipReason":null},"line":30,"column":5,"url":"file:///test/sample_test.dart"},"type":"testStart","time":220}""",
            """{"testID":4,"error":"Expected: <42>\n  Actual: <10>","stackTrace":"package:sample/sample_test.dart 32:7 main.<fn>","isFailure":true,"type":"error","time":250}""",
            """{"testID":4,"result":"failure","skipped":false,"hidden":false,"type":"testDone","time":260}""",
            """{"success":false,"type":"done","time":300}"""
        )

        val summary = FlutterTestReportParser.parseJsonLines(sampleJsonLines)

        assertEquals(3, summary.totalCount) // Hidden testID:1 excluded
        assertEquals(1, summary.passedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(300L, summary.durationMs)
        assertFalse(summary.success)

        val failedTest = summary.testCases.first { it.id == 4 }
        assertEquals("sample test fails", failedTest.name)
        assertEquals("test/sample_test.dart", failedTest.suitePath)
        assertEquals(30, failedTest.line)
        assertEquals(1, failedTest.errors.size)
        assertTrue(failedTest.errors.first().error.contains("Expected: <42>"))
        assertTrue(failedTest.errors.first().stackTrace.contains("sample_test.dart 32:7"))
    }

    @Test
    fun `generates valid JUnit XML report`() {
        val tempDir = Files.createTempDirectory("flutter-junit-test").toFile()
        val xmlFile = File(tempDir, "test-report.xml")

        val summary = FlutterTestRunSummary(
            totalCount = 2,
            passedCount = 1,
            failedCount = 1,
            skippedCount = 0,
            durationMs = 1500L,
            success = false,
            testCases = listOf(
                FlutterTestCaseResult(
                    id = 1,
                    name = "passes",
                    suitePath = "test/widget_test.dart",
                    line = 12,
                    column = 3,
                    url = null,
                    skipped = false,
                    skipReason = null,
                    result = "success",
                    durationMs = 500L
                ),
                FlutterTestCaseResult(
                    id = 2,
                    name = "fails with special characters < > & \"",
                    suitePath = "test/widget_test.dart",
                    line = 25,
                    column = 3,
                    url = null,
                    skipped = false,
                    skipReason = null,
                    result = "failure",
                    durationMs = 1000L,
                    errors = listOf(
                        FlutterTestError(
                            error = "Expected <foo> & 'bar' but got <baz>",
                            stackTrace = "test/widget_test.dart:30:5"
                        )
                    )
                )
            )
        )

        FlutterTestReportParser.generateJUnitXml(summary, "test_suite", xmlFile)

        assertTrue(xmlFile.exists())
        val xmlContent = xmlFile.readText()
        assertTrue(xmlContent.contains("""<testsuite name="test_suite" tests="2" failures="1" errors="0" skipped="0" time="1.500">"""))
        assertTrue(xmlContent.contains("""<testcase name="passes" classname="test/widget_test.dart" time="0.500"/>"""))
        assertTrue(xmlContent.contains("""name="fails with special characters &lt; &gt; &amp; &quot;""""))
        assertTrue(xmlContent.contains("""<failure message="Expected &lt;foo&gt; &amp; &apos;bar&apos; but got &lt;baz&gt;">"""))
        assertTrue(xmlContent.contains("<![CDATA[Expected <foo> & 'bar' but got <baz>\ntest/widget_test.dart:30:5]]>"))
    }

    @Test
    fun `formats concise Markdown failure summary for LLMs`() {
        val summary = FlutterTestRunSummary(
            totalCount = 2,
            passedCount = 1,
            failedCount = 1,
            skippedCount = 0,
            durationMs = 1200L,
            success = false,
            testCases = listOf(
                FlutterTestCaseResult(
                    id = 1,
                    name = "widget renders properly",
                    suitePath = "test/home_test.dart",
                    line = 15,
                    column = null,
                    url = null,
                    skipped = false,
                    skipReason = null,
                    result = "failure",
                    durationMs = 1200L,
                    errors = listOf(
                        FlutterTestError(
                            error = "TestFailure: Timed out waiting for Found 0 widgets with text 'Submit'",
                            stackTrace = "package:flutter_test/src/widget_tester.dart 45:10\ntest/home_test.dart 22:5 main.<fn>"
                        )
                    )
                )
            )
        )

        val formatted = FlutterTestReportParser.formatFailureSummary(summary)

        assertTrue(formatted.contains("### Test Failures: 1 of 2 failed (1200ms)"))
        assertTrue(formatted.contains("1. **widget renders properly**"))
        assertTrue(formatted.contains("- **Location**: `test/home_test.dart:15`"))
        assertTrue(formatted.contains("TestFailure: Timed out waiting for Found 0 widgets with text 'Submit'"))
        assertTrue(formatted.contains("test/home_test.dart 22:5"))
    }

    @Test
    fun `combines isolated target summaries accurately`() {
        val summary1 = FlutterTestRunSummary(
            totalCount = 2,
            passedCount = 2,
            failedCount = 0,
            skippedCount = 0,
            durationMs = 800L,
            success = true,
            testCases = listOf(
                FlutterTestCaseResult(1, "t1", "test/a_test.dart", 1, 1, null, false, null, "success", 400L),
                FlutterTestCaseResult(2, "t2", "test/a_test.dart", 2, 1, null, false, null, "success", 400L)
            )
        )
        val summary2 = FlutterTestRunSummary(
            totalCount = 1,
            passedCount = 0,
            failedCount = 1,
            skippedCount = 0,
            durationMs = 500L,
            success = false,
            testCases = listOf(
                FlutterTestCaseResult(3, "t3", "test/b_test.dart", 5, 1, null, false, null, "failure", 500L)
            )
        )

        val combined = FlutterTestReportParser.combineSummaries(listOf(summary1, summary2))

        assertEquals(3, combined.totalCount)
        assertEquals(2, combined.passedCount)
        assertEquals(1, combined.failedCount)
        assertEquals(0, combined.skippedCount)
        assertEquals(1300L, combined.durationMs)
        assertFalse(combined.success)
        assertEquals(3, combined.testCases.size)
    }

    @Test
    fun `parses lcov info file and calculates coverage percentage`() {
        val tempDir = Files.createTempDirectory("flutter-lcov-test").toFile()
        val lcovFile = File(tempDir, "lcov.info")
        lcovFile.writeText(
            """
            TN:
            SF:lib/src/foo.dart
            DA:1,1
            DA:2,0
            LF:20
            LH:15
            end_of_record
            SF:lib/src/bar.dart
            DA:1,1
            LF:80
            LH:65
            end_of_record
            """.trimIndent()
        )

        val coverage = FlutterTestReportParser.parseLcovCoverage(lcovFile)

        assertNotNull(coverage)
        assertEquals(100, coverage!!.linesFound)
        assertEquals(80, coverage.linesHit)
        assertEquals(80.0, coverage.coveragePercent)
    }
}
