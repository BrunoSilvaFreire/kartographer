package io.kartographer.tasks.dart

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TestTaskSupportTest {

    @Test
    fun `merges isolated coverage parts without counting a file twice`(@TempDir tempDir: Path) {
        val first = tempDir.resolve("part0.lcov.info").toFile()
        first.writeText(
            """
            SF:lib/a.dart
            DA:1,1
            DA:2,0
            LF:2
            LH:1
            end_of_record
            """.trimIndent()
        )
        val second = tempDir.resolve("part1.lcov.info").toFile()
        second.writeText(
            """
            SF:lib/a.dart
            DA:1,2
            DA:2,1
            LF:2
            LH:2
            end_of_record
            SF:lib/b.dart
            DA:7,0
            LF:1
            LH:0
            end_of_record
            """.trimIndent()
        )
        val merged = tempDir.resolve("lcov.info").toFile()

        TestTaskSupport.mergeCoverage(listOf(first, second), merged)

        val summary = DartTestReportParser.parseLcovCoverage(merged)
        assertNotNull(summary)
        // a.dart counted once (2 lines), not twice: 3 lines found, 2 of them hit.
        assertEquals(3, summary!!.linesFound)
        assertEquals(2, summary.linesHit)
        val a = summary.files.single { it.path == "lib/a.dart" }
        assertEquals(mapOf(1 to 3, 2 to 1), a.lineHits)
        assertFalse(first.exists(), "parts are deleted once merged")
        assertFalse(second.exists())
    }

    @Test
    fun `leaves the target alone when no part was written`(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("part0.lcov.info").toFile()
        val target = tempDir.resolve("lcov.info").toFile()

        TestTaskSupport.mergeCoverage(listOf(missing), target)

        assertFalse(target.exists())
    }
}
