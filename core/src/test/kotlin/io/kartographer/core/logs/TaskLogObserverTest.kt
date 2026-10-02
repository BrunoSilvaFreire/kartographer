package io.kartographer.core.logs

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionObserver
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.NoInputs
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.local.HostExecutionEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class TaskLogObserverTest {

    private class ShellTask(name: String) : Task<NoInputs, NoOutputs>(name) {
        override suspend fun buildInputs(context: ExecutionContext) = NoInputs()

        override fun buildOutputs() = NoOutputs()

        override suspend fun execute(context: ExecutionContext, inputSet: NoInputs): ExecutionResult<NoOutputs> {
            val code = context.executionEnvironment!!.execute(
                "echo out1; echo err1 >&2; exit 3",
                context.projectDir.toString(),
                context.environment,
                false
            )
            return if (code == 0) success() else failure(RuntimeException("exit $code"), code)
        }
    }

    @Test
    fun `captures stdout stderr and completion while forwarding every event`(@TempDir tmp: Path) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))

        runBlocking {
            val store = FileTaskLogStore(tmp.resolve("runs"), TaskLogConfig(compress = false))
            val sink = store.openRun("run-42", Instant.now())

            val forwarded = mutableListOf<ExecutionEvent>()
            val delegate = object : ExecutionObserver {
                override fun onEvent(event: ExecutionEvent) {
                    forwarded.add(event)
                }
            }
            val observer = TaskLogObserver(delegate, "run-42", listOf(sink))

            val task = ShellTask("demo")
            val context = ExecutionContext(
                projectDir = tmp,
                environment = emptyMap(),
                worker = Worker(WorkerId("test-worker")),
                observer = observer,
                executionNode = task
            )

            // Real process output goes through HostExecutionEnvironment and reaches the observer.
            val host = HostExecutionEnvironment(context)
            val exitCode = host.execute("echo out1; echo err1 >&2; exit 3", tmp.toString(), emptyMap(), false)
            assertEquals(3, exitCode)

            // Synthetic lifecycle events cover what the scheduler would normally emit.
            observer.onEvent(ExecutionEvent.TaskStarted(task))
            observer.onEvent(
                ExecutionEvent.TaskCompleted(
                    task,
                    ExecutionResult.Failure(RuntimeException("boom"), 3, NoOutputs())
                )
            )
            sink.close()

            val lines = store.read("run-42").toList()
            assertTrue(lines.any { it.contains("stream=out") && it.endsWith(" out1") }, lines.toString())
            assertTrue(lines.any { it.contains("stream=err") && it.endsWith(" err1") }, lines.toString())
            assertTrue(lines.any { it.contains("stream=meta start") }, lines.toString())
            assertTrue(
                lines.any { it.contains("end status=FAILED exit=3 duration_ms=") },
                lines.toString()
            )

            // Two TaskOutput from the process plus the two synthetic lifecycle events.
            assertEquals(4, forwarded.size)
            assertTrue(forwarded.any { it is ExecutionEvent.TaskStarted })
            assertTrue(forwarded.any { it is ExecutionEvent.TaskCompleted })
        }
    }
}
