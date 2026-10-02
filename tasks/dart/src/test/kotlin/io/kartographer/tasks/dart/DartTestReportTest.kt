package io.kartographer.tasks.dart

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DartTestReportTest {

    @Test
    fun `parses detailed lcov file into per-file and line-level coverage`(@TempDir tempDir: Path) {
        val lcovFile = tempDir.resolve("sample.lcov").toFile()
        lcovFile.writeText(
            """
            SF:lib/src/a.dart
            DA:1,3
            DA:2,0
            DA:5,1
            LF:3
            LH:2
            end_of_record
            SF:lib/src/b.dart
            DA:10,0
            DA:11,0
            LF:2
            LH:0
            end_of_record
            """.trimIndent()
        )

        val summary = DartTestReportParser.parseLcovCoverage(lcovFile)
        assertNotNull(summary)
        assertEquals(5, summary!!.linesFound)
        assertEquals(2, summary.linesHit)
        assertEquals(40.0, summary.coveragePercent)
        assertEquals(2, summary.files.size)

        val fileA = summary.files[0]
        assertEquals("lib/src/a.dart", fileA.path)
        assertEquals(3, fileA.linesFound)
        assertEquals(2, fileA.linesHit)
        assertEquals(66.67, fileA.coveragePercent)
        assertEquals(3, fileA.lineHits[1])
        assertEquals(0, fileA.lineHits[2])
        assertEquals(1, fileA.lineHits[5])
        assertEquals(listOf(2), fileA.uncoveredLines)

        val fileB = summary.files[1]
        assertEquals("lib/src/b.dart", fileB.path)
        assertEquals(2, fileB.linesFound)
        assertEquals(0, fileB.linesHit)
        assertEquals(0.0, fileB.coveragePercent)
        assertEquals(listOf(10, 11), fileB.uncoveredLines)
    }

    @Test
    fun `formats uncovered line numbers into compact ranges`() {
        assertEquals("", DartTestReportParser.formatLineRanges(emptyList()))
        assertEquals("7", DartTestReportParser.formatLineRanges(listOf(7)))
        assertEquals("1-5", DartTestReportParser.formatLineRanges(listOf(1, 2, 3, 4, 5)))
        assertEquals("9, 11, 28-30, 112", DartTestReportParser.formatLineRanges(listOf(9, 11, 28, 29, 30, 112)))
        assertEquals("2, 4-6, 10-11", DartTestReportParser.formatLineRanges(listOf(2, 4, 5, 6, 10, 11)))
    }

    @Test
    fun `html report renders coverage section and highlights source code lines`(@TempDir tempDir: Path) {
        val srcFile = tempDir.resolve("lib/calc.dart").toFile()
        srcFile.parentFile.mkdirs()
        srcFile.writeText(
            """
            int add(int a, int b) {
              return a + b;
            }
            int sub(int a, int b) {
              return a - b;
            }
            """.trimIndent()
        )

        val lcovFile = tempDir.resolve("coverage.lcov").toFile()
        lcovFile.writeText(
            """
            SF:${srcFile.absolutePath}
            DA:1,2
            DA:2,2
            DA:4,0
            DA:5,0
            LF:4
            LH:2
            end_of_record
            """.trimIndent()
        )

        val coverage = DartTestReportParser.parseLcovCoverage(lcovFile)
        assertNotNull(coverage)

        val summary = DartTestRunSummary(
            totalCount = 1,
            passedCount = 1,
            failedCount = 0,
            skippedCount = 0,
            durationMs = 120L,
            success = true,
            testCases = listOf(
                DartTestCaseResult(
                    id = 1,
                    name = "add works",
                    suitePath = "test/calc_test.dart",
                    line = 5,
                    column = 1,
                    url = null,
                    skipped = false,
                    skipReason = null,
                    result = "success",
                    durationMs = 120L
                )
            )
        )

        val htmlFile = tempDir.resolve("report.html").toFile()
        val generated = DartTestReportParser.generateHtmlReport(
            suites = listOf(DartTestReportParser.SuiteReport("calc.test", summary, coverage = coverage)),
            htmlFile = htmlFile,
            title = "Calc Test Report",
            coverage = coverage
        )

        assertTrue(generated)
        assertTrue(htmlFile.exists())
        val html = htmlFile.readText()

        // Self-contained constraints (no external scripts or remote URLs)
        assertTrue(!html.contains("<script src"), "must not reference external scripts")
        assertTrue(html.contains("<script>"), "includes inline table sorting script")
        assertTrue(html.contains("sortCoverageTable"), "includes sortCoverageTable JS function")
        assertTrue(!html.contains("http://") && !html.contains("https://"), "must not reference external URLs")

        // Sortable table headers and row attributes
        assertTrue(html.contains("class=\"sort-btn\""), "sort buttons missing from headers")
        assertTrue(html.contains("sort-icon"), "sort icons missing from headers")
        assertTrue(html.contains("data-col=\"file\""), "file column sort attribute missing")
        assertTrue(html.contains("data-col=\"cov\""), "coverage column sort attribute missing")
        assertTrue(html.contains("data-file=\""), "row data-file attribute missing")
        assertTrue(html.contains("data-cov=\"50.0\""), "row data-cov attribute missing")

        // Coverage summary presence
        assertTrue(html.contains("Coverage Report"), "coverage section title missing")
        assertTrue(html.contains("50.0%"), "coverage percent missing")
        assertTrue(html.contains("2 / 4 lines"), "line counts missing")
        assertTrue(html.contains("coverage-bar"), "progress bar missing")
        assertTrue(html.contains("calc.dart"), "file name missing from coverage table")

        // Uncovered lines range
        assertTrue(html.contains("4-5"), "uncovered lines range missing from table")

        // Line-by-line source visualization
        assertTrue(html.contains("cov-hit"), "covered line class missing")
        assertTrue(html.contains("cov-miss"), "missed line class missing")
        assertTrue(html.contains("cov-none"), "non-executable line class missing")
        assertTrue(html.contains("2x"), "hit count badge missing")
        assertTrue(html.contains("0x"), "miss hit count badge missing")
        assertTrue(html.contains("return a + b;"), "source code text missing")
        assertTrue(html.contains("return a - b;"), "source code text missing")
    }

    @Test
    fun `html report falls back to line map when source file is not on disk`(@TempDir tempDir: Path) {
        val coverage = LcovCoverageSummary(
            linesFound = 3,
            linesHit = 2,
            coveragePercent = 66.67,
            files = listOf(
                LcovFileCoverage(
                    path = "virtual/nonexistent.dart",
                    linesFound = 3,
                    linesHit = 2,
                    coveragePercent = 66.67,
                    lineHits = mapOf(10 to 5, 20 to 1, 30 to 0),
                    uncoveredLines = listOf(30)
                )
            )
        )

        val summary = DartTestRunSummary(
            totalCount = 1,
            passedCount = 1,
            failedCount = 0,
            skippedCount = 0,
            durationMs = 50L,
            success = true,
            testCases = emptyList()
        )

        val htmlFile = tempDir.resolve("report.html").toFile()
        val generated = DartTestReportParser.generateHtmlReport(
            suites = listOf(DartTestReportParser.SuiteReport("virtual.test", summary, coverage = coverage)),
            htmlFile = htmlFile,
            title = "Virtual Report",
            coverage = coverage
        )

        assertTrue(generated)
        val html = htmlFile.readText()
        assertTrue(html.contains("virtual/nonexistent.dart"))
        assertTrue(html.contains("Line 10"))
        assertTrue(html.contains("Line 30"))
        assertTrue(html.contains("cov-hit"))
        assertTrue(html.contains("cov-miss"))
    }

    @Test
    fun `supports sorting coverages on the model and in html generation`(@TempDir tempDir: Path) {
        val fileA = LcovFileCoverage("z_last.dart", linesFound = 10, linesHit = 9, coveragePercent = 90.0)
        val fileB = LcovFileCoverage("a_first.dart", linesFound = 10, linesHit = 2, coveragePercent = 20.0)
        val fileC = LcovFileCoverage("m_middle.dart", linesFound = 10, linesHit = 5, coveragePercent = 50.0)

        val summary = LcovCoverageSummary(
            linesFound = 30,
            linesHit = 16,
            coveragePercent = 53.33,
            files = listOf(fileA, fileB, fileC)
        )

        // Test sorting methods on LcovCoverageSummary
        val byCoverageAsc = summary.sortedByCoverage(ascending = true)
        assertEquals(listOf("a_first.dart", "m_middle.dart", "z_last.dart"), byCoverageAsc.files.map { it.path })

        val byCoverageDesc = summary.sortedByCoverage(ascending = false)
        assertEquals(listOf("z_last.dart", "m_middle.dart", "a_first.dart"), byCoverageDesc.files.map { it.path })

        val byPath = summary.sortedByPath()
        assertEquals(listOf("a_first.dart", "m_middle.dart", "z_last.dart"), byPath.files.map { it.path })

        val byMissedDesc = summary.sortedByMissed(descending = true)
        assertEquals(listOf("a_first.dart", "m_middle.dart", "z_last.dart"), byMissedDesc.files.map { it.path })

        // Test in HTML report using coverageComparator
        val htmlFile = tempDir.resolve("sorted-report.html").toFile()
        val runSummary = DartTestRunSummary(1, 1, 0, 0, 10L, true, emptyList())
        DartTestReportParser.generateHtmlReport(
            suites = listOf(DartTestReportParser.SuiteReport("test", runSummary, coverage = summary)),
            htmlFile = htmlFile,
            title = "Sorted Coverage Report",
            coverage = summary,
            coverageComparator = DartTestReportParser.CoverageSort.BY_COVERAGE_DESC
        )

        val html = htmlFile.readText()
        val posZ = html.indexOf("z_last.dart")
        val posM = html.indexOf("m_middle.dart")
        val posA = html.indexOf("a_first.dart")

        assertTrue(posZ < posM, "z_last.dart (90%) should appear before m_middle.dart (50%)")
        assertTrue(posM < posA, "m_middle.dart (50%) should appear before a_first.dart (20%)")
        assertTrue(html.contains("""data-col="cov" data-sort="desc""""), "coverage header should have initial desc sort")
        assertTrue(html.contains("▼"), "coverage header should display desc sort arrow")
    }
}
