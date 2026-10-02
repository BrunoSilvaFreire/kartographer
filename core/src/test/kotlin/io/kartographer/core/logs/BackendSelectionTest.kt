package io.kartographer.core.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackendSelectionTest {

    private fun config(backend: TaskLogBackend) = TaskLogConfig(backend = backend)

    @Test
    fun `auto on linux with journald keeps file and journald`() {
        assertEquals(
            listOf(TaskLogBackend.FILE, TaskLogBackend.JOURNALD),
            selectBackends(config(TaskLogBackend.AUTO), "Linux", journaldAvailable = true, eventLogAvailable = false)
        )
    }

    @Test
    fun `auto on linux without journald keeps file only`() {
        assertEquals(
            listOf(TaskLogBackend.FILE),
            selectBackends(config(TaskLogBackend.AUTO), "Linux", journaldAvailable = false, eventLogAvailable = false)
        )
    }

    @Test
    fun `auto on windows keeps file and event log`() {
        assertEquals(
            listOf(TaskLogBackend.FILE, TaskLogBackend.EVENTLOG),
            selectBackends(config(TaskLogBackend.AUTO), "Windows 11", journaldAvailable = false, eventLogAvailable = true)
        )
    }

    @Test
    fun `explicit journald on windows falls back to file`() {
        assertEquals(
            listOf(TaskLogBackend.FILE),
            selectBackends(config(TaskLogBackend.JOURNALD), "Windows 11", journaldAvailable = true, eventLogAvailable = false)
        )
    }

    @Test
    fun `explicit journald on linux without journald falls back to file`() {
        assertEquals(
            listOf(TaskLogBackend.FILE),
            selectBackends(config(TaskLogBackend.JOURNALD), "Linux", journaldAvailable = false, eventLogAvailable = false)
        )
    }

    @Test
    fun `off selects no backends`() {
        assertTrue(
            selectBackends(config(TaskLogBackend.OFF), "Linux", journaldAvailable = true, eventLogAvailable = true).isEmpty()
        )
    }

    @Test
    fun `an unknown backend value falls back to auto`() {
        assertEquals(TaskLogBackend.AUTO, TaskLogConfig.from(mapOf("KARTO_LOG_BACKEND" to "nonsense")).backend)
    }

    @Test
    fun `defaults are used when the environment is empty`() {
        assertEquals(TaskLogConfig(), TaskLogConfig.from(emptyMap()))
    }

    @Test
    fun `parses explicit values`() {
        val config = TaskLogConfig.from(
            mapOf(
                "KARTO_LOG_BACKEND" to "file",
                "KARTO_LOG_MAX_RUNS" to "7",
                "KARTO_LOG_MAX_AGE_DAYS" to "3",
                "KARTO_LOG_MAX_TOTAL_MB" to "9",
                "KARTO_LOG_COMPRESS" to "false"
            )
        )
        assertEquals(TaskLogBackend.FILE, config.backend)
        assertEquals(7, config.maxRuns)
        assertEquals(3, config.maxAgeDays)
        assertEquals(9, config.maxTotalMb)
        assertEquals(false, config.compress)
    }

    @Test
    fun `bad values fall back to defaults`() {
        val config = TaskLogConfig.from(
            mapOf(
                "KARTO_LOG_MAX_RUNS" to "abc",
                "KARTO_LOG_MAX_AGE_DAYS" to "-3",
                "KARTO_LOG_MAX_TOTAL_MB" to "x",
                "KARTO_LOG_COMPRESS" to "maybe"
            )
        )
        val defaults = TaskLogConfig()
        assertEquals(defaults.maxRuns, config.maxRuns)
        assertEquals(defaults.maxAgeDays, config.maxAgeDays)
        assertEquals(defaults.maxTotalMb, config.maxTotalMb)
        assertEquals(defaults.compress, config.compress)
    }
}
