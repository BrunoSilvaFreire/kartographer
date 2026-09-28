package io.kartographer

import io.kartographer.local.LocalProcessExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Paths

// Every case drives a POSIX shell and POSIX signal semantics (`sh -c`, `trap INT`, `pgrep`).
// They say nothing about the Windows path, which uses cmd.exe and ProcessHandle.destroy.
@EnabledOnOs(OS.LINUX, OS.MAC)
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

    @Test
    fun testStdinStreamingChannel() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val executor = LocalProcessExecutor(
            stdoutConsumer = { stdoutLines.add(it) }
        )
        executor.stdinChannel = channel

        channel.trySend("line1\n")
        channel.trySend("line2\n")

        val exitCode = executor.execute(
            command = "sh -c 'read l1; echo \"first: \$l1\"; read l2; echo \"second: \$l2\"'",
            workingDir = Paths.get("").toAbsolutePath().toString(),
            environment = emptyMap()
        )

        assertEquals(0, exitCode)
        assertTrue(stdoutLines.contains("first: line1"))
        assertTrue(stdoutLines.contains("second: line2"))
    }

    @Test
    fun testCancellationPropagatesSigintToChildProcess() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val executor = LocalProcessExecutor(
            stdoutConsumer = { stdoutLines.add(it) }
        )

        val job = launch(Dispatchers.IO) {
            executor.execute(
                command = "sh -c 'trap \"echo TRAPPED_SIGINT; exit 0\" INT; echo READY; while true; do sleep 0.05; done'",
                workingDir = Paths.get("").toAbsolutePath().toString(),
                environment = emptyMap()
            )
        }

        // Wait until child is running and ready
        val deadline = System.currentTimeMillis() + 5000
        while (!stdoutLines.contains("READY") && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertTrue(stdoutLines.contains("READY"))

        // Cancel coroutine: should send SIGINT to child process
        job.cancel()
        job.join()

        assertTrue(
            stdoutLines.contains("TRAPPED_SIGINT"),
            "Expected process to trap SIGINT on cancellation. Lines received: $stdoutLines"
        )
    }

    @Test
    fun testCancellationKillsUnresponsiveProcessAfterGracePeriod() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val executor = LocalProcessExecutor(
            stdoutConsumer = { stdoutLines.add(it) }
        )
        val origGrace = LocalProcessExecutor.defaultGracePeriodMillis
        LocalProcessExecutor.defaultGracePeriodMillis = 300L

        try {
            val job = launch(Dispatchers.IO) {
                executor.execute(
                    command = "sh -c 'trap \"\" INT; echo READY_UNRESPONSIVE; while true; do sleep 0.05; done'",
                    workingDir = Paths.get("").toAbsolutePath().toString(),
                    environment = emptyMap()
                )
            }

            val deadline = System.currentTimeMillis() + 5000
            while (!stdoutLines.contains("READY_UNRESPONSIVE") && System.currentTimeMillis() < deadline) {
                delay(20)
            }
            assertTrue(stdoutLines.contains("READY_UNRESPONSIVE"))

            val startCancel = System.currentTimeMillis()
            job.cancel()
            job.join()
            val duration = System.currentTimeMillis() - startCancel

            // Escalation should have killed the process within ~1-2 seconds
            assertTrue(duration < 3000, "Expected unresponsive process to be killed promptly, took ${duration}ms")
        } finally {
            LocalProcessExecutor.defaultGracePeriodMillis = origGrace
        }
    }

    @Test
    fun testDescendantProcessesKilledOnCancellation() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val executor = LocalProcessExecutor(
            stdoutConsumer = { stdoutLines.add(it) }
        )

        // Spawn a multi-tier hierarchy: sh -> sh -> sleep
        val job = launch(Dispatchers.IO) {
            executor.execute(
                command = "sh -c 'sh -c \"echo NESTED_READY; sleep 60\"'",
                workingDir = Paths.get("").toAbsolutePath().toString(),
                environment = emptyMap()
            )
        }

        val deadline = System.currentTimeMillis() + 5000
        while (!stdoutLines.contains("NESTED_READY") && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertTrue(stdoutLines.contains("NESTED_READY"))

        job.cancel()
        job.join()

        // Verify that no lingering 'sleep 60' or child processes remain
        val checkProc = ProcessBuilder("sh", "-c", "pgrep -f '[s]leep 60' || true").start()
        val pids = checkProc.inputStream.bufferedReader().readText().trim()
        checkProc.waitFor()
        assertEquals("", pids, "Expected no lingering descendant sleep processes, found PIDs: $pids")
    }
}

