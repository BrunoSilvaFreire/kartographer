package io.kartographer.core.logs

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionObserver
import io.kartographer.core.model.ExecutionResult
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Persisted task logs for the local `karto run` path.
 *
 * The daemon already streams stdout/stderr to the CLI through [ExecutionObserver]; this package
 * adds a decorator that tees those same events into a per-run file and, when the host supports
 * it, into journald or the Windows Event Log. The read side lives here in `core` (not `daemon`)
 * so the CLI can inspect past runs without depending on the daemon's heavy classpath.
 */

/** Which sinks a run should write to. `AUTO` and `OFF` are configuration-only; they never end up in a resolved list. */
enum class TaskLogBackend { AUTO, FILE, JOURNALD, EVENTLOG, OFF }

/**
 * Log configuration, parsed from `KARTO_LOG_*` environment variables.
 *
 * Bad values (non-numeric, out of range, unknown backend) silently fall back to the default for
 * that field, so a typo never disables logging or fails a run.
 */
data class TaskLogConfig(
    val backend: TaskLogBackend = TaskLogBackend.AUTO,
    val maxRuns: Int = 50,
    val maxAgeDays: Int = 14,
    val maxTotalMb: Int = 500,
    val compress: Boolean = true
) {
    companion object {
        fun from(env: Map<String, String>): TaskLogConfig {
            val defaults = TaskLogConfig()
            return TaskLogConfig(
                backend = env["KARTO_LOG_BACKEND"]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { TaskLogBackend.valueOf(it.uppercase()) }.getOrNull() }
                    ?: defaults.backend,
                maxRuns = env["KARTO_LOG_MAX_RUNS"]?.toIntOrNull()?.takeIf { it >= 0 } ?: defaults.maxRuns,
                maxAgeDays = env["KARTO_LOG_MAX_AGE_DAYS"]?.toIntOrNull()?.takeIf { it >= 0 } ?: defaults.maxAgeDays,
                maxTotalMb = env["KARTO_LOG_MAX_TOTAL_MB"]?.toIntOrNull()?.takeIf { it >= 0 } ?: defaults.maxTotalMb,
                compress = env["KARTO_LOG_COMPRESS"]?.let(::parseBool) ?: defaults.compress
            )
        }

        private fun parseBool(value: String): Boolean? = when (value.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }
}

/**
 * Resolves the concrete backends for a host.
 *
 * The file store is written for every value except [TaskLogBackend.OFF] because `karto logs`
 * depends on it. An explicit native backend that is not available on this host degrades to the
 * file store alone; [AUTO] simply adds whichever native backend is present.
 */
fun selectBackends(
    config: TaskLogConfig,
    osName: String,
    journaldAvailable: Boolean,
    eventLogAvailable: Boolean
): List<TaskLogBackend> {
    val linux = osName.lowercase().contains("linux")
    val windows = osName.lowercase().contains("win")
    return when (config.backend) {
        TaskLogBackend.OFF -> emptyList()
        TaskLogBackend.FILE -> listOf(TaskLogBackend.FILE)
        TaskLogBackend.JOURNALD ->
            if (linux && journaldAvailable) listOf(TaskLogBackend.FILE, TaskLogBackend.JOURNALD) else listOf(TaskLogBackend.FILE)
        TaskLogBackend.EVENTLOG ->
            if (windows && eventLogAvailable) listOf(TaskLogBackend.FILE, TaskLogBackend.EVENTLOG) else listOf(TaskLogBackend.FILE)
        TaskLogBackend.AUTO -> buildList {
            add(TaskLogBackend.FILE)
            if (linux && journaldAvailable) add(TaskLogBackend.JOURNALD)
            if (windows && eventLogAvailable) add(TaskLogBackend.EVENTLOG)
        }
    }
}

/**
 * One log line, identical across every backend.
 *
 * `format()` is the single shared formatter; the native sinks reuse it as their message text and
 * attach the structured fields separately.
 */
data class TaskLogRecord(
    val time: Instant,
    val runId: String,
    val task: String,
    val invocationId: String,
    val stream: Stream,
    val text: String,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val failed: Boolean = false
) {
    enum class Stream(val label: String) { OUT("out"), ERR("err"), META("meta") }

    fun format(): String =
        "${FOR_TIMESTAMP.format(time)} run=$runId task=$task inv=$invocationId stream=${stream.label} $text"

    companion object {
        val FOR_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

/** Destination for [TaskLogRecord]s. Errors are contained by the caller, never propagated to a run. */
interface TaskLogSink {
    fun write(record: TaskLogRecord)
    fun close()
}

/**
 * Per-project run logs under `<projectDir>/.kartographer/logs/runs`.
 *
 * One file per run, named `<yyyyMMdd'T'HHmmss'Z'>-<runId>.log`; the sortable name doubles as the
 * timestamp lookup. This class is free of JNA types so the CLI can use the read side directly.
 */
class FileTaskLogStore(val root: Path, private val config: TaskLogConfig) {

    data class RunFile(val name: String, val path: Path, val sizeBytes: Long, val modified: Instant)

    private val logger = LoggerFactory.getLogger(FileTaskLogStore::class.java)

    /** Opens the sink for one run. Pruning happens separately, at run start. */
    fun openRun(runId: String, startedAt: Instant): TaskLogSink {
        Files.createDirectories(root)
        val path = root.resolve("${runFileName(runId, startedAt)}.log")
        val writer = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        return FileRunSink(path, writer, config.compress)
    }

    /**
     * Deletes old runs: by age, then by count, then by total size, always oldest first.
     *
     * Called when a run starts, so the current run's file does not exist yet; [exclude] exists so
     * a caller may still protect a live file defensively.
     */
    fun prune(now: Instant, exclude: Path? = null) {
        val excluded = exclude?.toAbsolutePath()?.normalize()
        val survivors = runFiles()
            .filter { excluded == null || it.path.toAbsolutePath().normalize() != excluded }
            .sortedBy { it.name }
            .toMutableList()

        val cutoff = now.minus(config.maxAgeDays.toLong(), ChronoUnit.DAYS)
        survivors.removeAll { run ->
            if (run.modified.isBefore(cutoff)) {
                delete(run.path)
                true
            } else {
                false
            }
        }

        while (survivors.size > config.maxRuns) {
            delete(survivors.removeAt(0).path)
        }

        var totalBytes = survivors.sumOf { it.sizeBytes }
        val maxBytes = config.maxTotalMb.toLong() * 1024 * 1024
        while (totalBytes > maxBytes && survivors.isNotEmpty()) {
            val oldest = survivors.removeAt(0)
            delete(oldest.path)
            totalBytes -= oldest.sizeBytes
        }
    }

    /** Every stored run, newest first. */
    fun listRuns(): List<RunFile> = runFiles().sortedByDescending { it.name }

    /**
     * Reads a run's lines.
     *
     * [runRef] is a run id or timestamp prefix; null/blank selects the newest run. [taskFilter]
     * keeps only lines whose `task=` field matches exactly. The first matching run wins.
     */
    fun read(runRef: String?, taskFilter: String? = null): Sequence<String> {
        val run = resolveRun(runRef) ?: return emptySequence()
        val lines = readLines(run.path)
        val selected = if (taskFilter == null) lines else lines.filter { taskOf(it) == taskFilter }
        return selected.asSequence()
    }

    /** Resolves a run reference to a stored file, newest first. */
    fun resolveRun(runRef: String?): RunFile? {
        val runs = listRuns()
        if (runs.isEmpty()) return null
        if (runRef.isNullOrBlank()) return runs.first()
        // TODO: `contains` lets short refs (e.g. "1") hit arbitrary runs; match timestamp prefix or the runId part's prefix only, as the plan specifies.
        return runs.firstOrNull { it.name.startsWith(runRef) || it.name.contains(runRef) }
    }

    private fun runFileName(runId: String, startedAt: Instant): String =
        "${FILE_STAMP.format(startedAt)}-$runId"

    private fun runFiles(): List<RunFile> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.newDirectoryStream(root).use { stream ->
            return stream
                .filter { it.fileName.toString().let { name -> name.endsWith(".log") || name.endsWith(".log.gz") } }
                .mapNotNull { path ->
                    runCatching {
                        RunFile(
                            name = path.fileName.toString(),
                            path = path,
                            sizeBytes = Files.size(path),
                            modified = Files.getLastModifiedTime(path).toInstant()
                        )
                    }.getOrNull()
                }
        }
    }

    private fun readLines(path: Path): List<String> =
        if (path.fileName.toString().endsWith(".gz")) {
            GZIPInputStream(Files.newInputStream(path)).bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }
        } else {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { it.readLines() }
        }

    private fun delete(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { logger.debug("Could not delete old run log {}: {}", path, it.toString()) }
    }

    companion object {
        val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** Extracts the `task=` field, which may itself contain spaces. */
        fun taskOf(line: String): String? {
            val marker = " task="
            val start = line.indexOf(marker)
            if (start < 0) return null
            val after = start + marker.length
            val end = line.indexOf(" inv=", after)
            return if (end < 0) line.substring(after) else line.substring(after, end)
        }
    }
}

/** A single run's file sink; synchronized because output lines arrive from several IO threads. */
private class FileRunSink(
    private val path: Path,
    private val writer: BufferedWriter,
    private val compress: Boolean
) : TaskLogSink {
    private val lock = Any()
    private var closed = false

    override fun write(record: TaskLogRecord) {
        synchronized(lock) {
            if (closed) return
            writer.write(record.format())
            writer.newLine()
            writer.flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching {
                writer.flush()
                writer.close()
            }
        }
        if (compress) {
            gzipAndDelete(path)
        }
    }

    private fun gzipAndDelete(source: Path) {
        if (!Files.exists(source)) return
        val gzPath = source.resolveSibling("${source.fileName}.gz")
        try {
            Files.newInputStream(source).use { input ->
                GZIPOutputStream(Files.newOutputStream(gzPath)).use { output -> input.copyTo(output) }
            }
            Files.deleteIfExists(source)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(gzPath) }
            LoggerFactory.getLogger(FileRunSink::class.java)
                .debug("Could not compress run log {}: {}", source, e.toString())
        }
    }
}

/**
 * Tees scheduler events into the task log sinks while forwarding every event to [delegate].
 *
 * The delegate is always called first, so a logging failure can never delay or drop the wire
 * stream. Sink errors are caught and logged once per run.
 */
class TaskLogObserver(
    private val delegate: ExecutionObserver?,
    private val runId: String,
    private val sinks: List<TaskLogSink>
) : ExecutionObserver {
    private val logger = LoggerFactory.getLogger(TaskLogObserver::class.java)
    private val startTimes = ConcurrentHashMap<String, Instant>()
    private val sinkFailureLogged = AtomicBoolean(false)

    override fun onEvent(event: ExecutionEvent) {
        delegate?.onEvent(event)
        if (sinks.isEmpty()) return
        try {
            record(event)
        } catch (t: Throwable) {
            if (sinkFailureLogged.compareAndSet(false, true)) {
                logger.warn("Task logging failed; the run continues without logs: {}", t.toString())
            }
        }
    }

    private fun record(event: ExecutionEvent) {
        when (event) {
            is ExecutionEvent.TaskStarted -> {
                val invocationId = event.task.executionId
                startTimes[invocationId] = Instant.now()
                write(
                    TaskLogRecord(
                        time = Instant.now(),
                        runId = runId,
                        task = event.task.name,
                        invocationId = invocationId,
                        stream = TaskLogRecord.Stream.META,
                        text = "start"
                    )
                )
            }

            is ExecutionEvent.TaskOutput -> write(
                TaskLogRecord(
                    time = Instant.now(),
                    runId = runId,
                    task = event.task.name,
                    invocationId = event.task.executionId,
                    stream = if (event.isError) TaskLogRecord.Stream.ERR else TaskLogRecord.Stream.OUT,
                    text = event.line,
                    failed = event.isError
                )
            )

            is ExecutionEvent.TaskCompleted -> {
                val invocationId = event.task.executionId
                val now = Instant.now()
                val startedAt = startTimes.remove(invocationId)
                val durationMs = startedAt?.let { Duration.between(it, now).toMillis() }
                val status = when (event.result) {
                    is ExecutionResult.Success<*> -> "SUCCESS"
                    is ExecutionResult.UpToDate<*> -> "UP_TO_DATE"
                    is ExecutionResult.Failure<*> -> "FAILED"
                }
                val failure = event.result as? ExecutionResult.Failure<*>
                val failed = failure != null
                write(
                    TaskLogRecord(
                        time = now,
                        runId = runId,
                        task = event.task.name,
                        invocationId = invocationId,
                        stream = TaskLogRecord.Stream.META,
                        // TODO: print `duration_ms=-` when the start time is unknown instead of a misleading 0.
                        text = "end status=$status exit=${failure?.exitCode ?: "-"} duration_ms=${durationMs ?: 0}",
                        exitCode = failure?.exitCode,
                        durationMs = durationMs,
                        failed = failed
                    )
                )
            }

            else -> Unit
        }
    }

    private fun write(record: TaskLogRecord) {
        for (sink in sinks) sink.write(record)
    }
}
