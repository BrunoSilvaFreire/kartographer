package io.kartographer.core.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit

class FileTaskLogStoreTest {

    private fun record(
        runId: String,
        task: String,
        text: String,
        stream: TaskLogRecord.Stream = TaskLogRecord.Stream.OUT,
        time: Instant = Instant.now()
    ) = TaskLogRecord(
        time = time,
        runId = runId,
        task = task,
        invocationId = "inv-$task",
        stream = stream,
        text = text
    )

    @Test
    fun `compresses on close and reads the run back`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(tmp, TaskLogConfig(compress = true))
        val sink = store.openRun("r1", Instant.parse("2026-09-29T10:00:00Z"))
        sink.write(record("r1", "demo", "hello"))
        sink.close()

        assertTrue(Files.exists(tmp.resolve("20260929T100000Z-r1.log.gz")))
        assertFalse(Files.exists(tmp.resolve("20260929T100000Z-r1.log")))

        val lines = store.read("r1").toList()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("stream=out hello"), lines[0])
    }

    @Test
    fun `keeps only the newest runs by count`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(
            tmp,
            TaskLogConfig(maxRuns = 3, maxAgeDays = 14, maxTotalMb = 100_000, compress = false)
        )
        for (i in 1..5) {
            val startedAt = Instant.parse("2026-09-29T10:00:0${i}Z")
            val sink = store.openRun("run-$i", startedAt)
            sink.write(record("run-$i", "t", "line $i", time = startedAt))
            sink.close()
        }

        store.prune(Instant.parse("2026-09-29T11:00:00Z"))

        val names = store.listRuns().map { it.name }
        assertEquals(3, names.size)
        assertTrue(names.none { it.contains("run-1") || it.contains("run-2") }, names.toString())
        assertTrue(names.any { it.contains("run-5") })
    }

    @Test
    fun `deletes runs older than the configured age`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(
            tmp,
            TaskLogConfig(maxRuns = 100, maxAgeDays = 1, maxTotalMb = 100_000, compress = false)
        )
        val oldSink = store.openRun("old", Instant.now().minus(10, ChronoUnit.DAYS))
        oldSink.write(record("old", "t", "old"))
        oldSink.close()

        val freshSink = store.openRun("fresh", Instant.now())
        freshSink.write(record("fresh", "t", "fresh"))
        freshSink.close()

        val oldRun = store.listRuns().first { it.name.contains("old") }
        Files.setLastModifiedTime(oldRun.path, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)))

        store.prune(Instant.now())

        assertFalse(Files.exists(oldRun.path))
        assertTrue(store.listRuns().any { it.name.contains("fresh") })
    }

    @Test
    fun `deletes oldest runs until the total size fits`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(
            tmp,
            TaskLogConfig(maxRuns = 100, maxAgeDays = 100_000, maxTotalMb = 1, compress = false)
        )
        val filler = "x".repeat(600_000)
        for (i in 1..4) {
            val startedAt = Instant.parse("2026-09-29T10:00:0${i}Z")
            val sink = store.openRun("run-$i", startedAt)
            sink.write(record("run-$i", "t", filler, time = startedAt))
            sink.close()
        }

        store.prune(Instant.parse("2026-09-29T11:00:00Z"))

        val remaining = store.listRuns()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].name.contains("run-4"), remaining[0].name)
        assertTrue(remaining.sumOf { it.sizeBytes } <= 1024L * 1024L)
    }

    @Test
    fun `never deletes the excluded current run`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(
            tmp,
            TaskLogConfig(maxRuns = 1, maxAgeDays = 100_000, maxTotalMb = 100_000, compress = false)
        )
        // The current run has the oldest timestamp, so without the exclusion it would be pruned.
        val currentStart = Instant.now().minus(5, ChronoUnit.DAYS)
        val currentPath = tmp.resolve("${FileTaskLogStore.FILE_STAMP.format(currentStart)}-current.log")
        val currentSink = store.openRun("current", currentStart)
        currentSink.write(record("current", "t", "current"))

        for (i in 1..2) {
            val startedAt = Instant.now()
            val sink = store.openRun("new-$i", startedAt)
            sink.write(record("new-$i", "t", "new $i"))
            sink.close()
        }

        store.prune(Instant.now(), exclude = currentPath)

        assertTrue(Files.exists(currentPath))
        assertEquals(2, store.listRuns().size)
        currentSink.close()
    }

    @Test
    fun `filters lines by task`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(tmp, TaskLogConfig(compress = false))
        val sink = store.openRun("r1", Instant.now())
        sink.write(record("r1", "alpha", "a1"))
        sink.write(record("r1", "beta", "b1"))
        sink.write(record("r1", "alpha", "a2"))
        sink.close()

        val beta = store.read("r1", "beta").toList()
        assertEquals(1, beta.size)
        assertTrue(beta[0].contains("task=beta"), beta[0])

        assertEquals(3, store.read("r1").toList().size)
    }

    @Test
    fun `resolves the latest run when no reference is given`(@TempDir tmp: Path) {
        val store = FileTaskLogStore(tmp, TaskLogConfig(compress = false))
        listOf("2026-09-29T10:00:00Z" to "first", "2026-09-29T10:00:05Z" to "second").forEach { (time, id) ->
            val sink = store.openRun(id, Instant.parse(time))
            sink.write(record(id, "t", id))
            sink.close()
        }

        val latest = store.read(null).toList()
        assertEquals(1, latest.size)
        assertTrue(latest[0].contains("run=second"), latest[0])
    }
}
