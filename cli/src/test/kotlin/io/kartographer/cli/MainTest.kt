package io.kartographer.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.InputStream

class MainTest {
    @Test
    fun `stdin polling does not start a blocking read while a prompt may arrive`() {
        var readCalled = false
        val input = object : InputStream() {
            override fun available() = 0
            override fun read(): Int {
                readCalled = true
                return -1
            }
        }

        assertEquals(0, readAvailableInput(input, ByteArray(16)))
        assertFalse(readCalled)
    }

    @Test
    fun `rewriteArgs leaves --ui equals form untouched`() {
        assertEquals(
            listOf("run", "--ui=tui", "prod/containers/push"),
            rewriteArgs(listOf("run", "--ui=tui", "prod/containers/push"))
        )
    }

    @Test
    fun `rewriteArgs leaves --ui space form untouched`() {
        assertEquals(
            listOf("run", "prod/containers/push", "--ui", "tui"),
            rewriteArgs(listOf("run", "prod/containers/push", "--ui", "tui"))
        )
    }

    @Test
    fun `rewriteArgs leaves project-dir and pipeline equals forms untouched`() {
        assertEquals(
            listOf("-d", "/tmp/proj", "--pipeline=foo.Pipeline", "run", "task"),
            rewriteArgs(listOf("-d", "/tmp/proj", "--pipeline=foo.Pipeline", "run", "task"))
        )
    }

    @Test
    fun `rewriteArgs converts unknown flag into a -P property`() {
        assertEquals(
            listOf("run", "prod/containers/push", "-P", "version=1.1.0-rc1"),
            rewriteArgs(listOf("run", "prod/containers/push", "--version", "1.1.0-rc1"))
        )
    }

    @Test
    fun `rewriteArgs converts bare unknown flag into a true property`() {
        assertEquals(
            listOf("run", "task", "-P", "force=true"),
            rewriteArgs(listOf("run", "task", "--force"))
        )
    }

    @Test
    fun `rewriteArgs turns help with a task into task-help`() {
        assertEquals(
            listOf("run", "--task-help", "task"),
            rewriteArgs(listOf("run", "--help", "task"))
        )
    }

    @Test
    fun `run UI uses preference unless explicitly overridden`() {
        assertEquals("plain", resolveUi(null, "plain", interactive = true))
        assertEquals("tui", resolveUi("tui", "plain", interactive = false))
        assertEquals("plain", resolveUi(null, "auto", interactive = false))
    }
}
