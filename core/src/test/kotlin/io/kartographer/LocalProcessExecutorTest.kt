package io.kartographer

import io.kartographer.local.LocalProcessExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class LocalProcessExecutorTest {

    @Test
    fun testDefaultExecutionAndOutputConsumer() = runBlocking {
        val lines = mutableListOf<String>()
        val executor = LocalProcessExecutor(
            stdoutConsumer = { lines.add(it) }
        )
        
        val exitCode = executor.execute(
            command = "echo 'hello world'",
            workingDir = Paths.get("").toAbsolutePath().toString(),
            environment = emptyMap()
        )
        
        assertEquals(0, exitCode)
        assertEquals(1, lines.size)
        assertEquals("hello world", lines[0])
    }

    @Test
    fun testStderrRedirectionAndConsumer() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val executor = LocalProcessExecutor(
            redirectErrorStream = false,
            stdoutConsumer = { stdoutLines.add(it) },
            stderrConsumer = { stderrLines.add(it) }
        )
        
        val exitCode = executor.execute(
            command = "echo 'to stdout' && echo 'to stderr' >&2",
            workingDir = Paths.get("").toAbsolutePath().toString(),
            environment = emptyMap()
        )
        
        assertEquals(0, exitCode)
        assertEquals(1, stdoutLines.size)
        assertEquals("to stdout", stdoutLines[0])
        assertEquals(1, stderrLines.size)
        assertEquals("to stderr", stderrLines[0])
    }

    @Test
    fun testStdinConfigurableInputString() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val executor = LocalProcessExecutor(
            inputString = "piped input data",
            stdoutConsumer = { stdoutLines.add(it) }
        )
        
        val exitCode = executor.execute(
            command = "cat",
            workingDir = Paths.get("").toAbsolutePath().toString(),
            environment = emptyMap()
        )
        
        assertEquals(0, exitCode)
        assertEquals(1, stdoutLines.size)
        assertEquals("piped input data", stdoutLines[0])
    }
}
