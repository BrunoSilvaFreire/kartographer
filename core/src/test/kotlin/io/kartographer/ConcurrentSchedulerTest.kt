package io.kartographer

import io.kartographer.core.*
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry
import io.kartographer.testing.ScriptedTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ConcurrentSchedulerTest {

    @BeforeEach
    fun setup() {
        TaskRegistry.clear()
    }

    @Test
    fun testParallelExecution() = runBlocking {
        var aStartedAt: Long = 0
        var bStartedAt: Long = 0
        var aFinishedAt: Long = 0
        var bFinishedAt: Long = 0
        var cStartedAt: Long = 0

        val a = task<ScriptedTask>("A") {
            action {
                aStartedAt = System.currentTimeMillis()
                delay(150)
                aFinishedAt = System.currentTimeMillis()
                Unit
            }
        }

        val b = task<ScriptedTask>("B") {
            action {
                bStartedAt = System.currentTimeMillis()
                delay(150)
                bFinishedAt = System.currentTimeMillis()
                Unit
            }
        }

        val c = task<ScriptedTask>("C") {
            dependsOn(a, b)
            action {
                cStartedAt = System.currentTimeMillis()
                Unit
            }
        }

        val scheduler = ConcurrentScheduler(TaskRegistry.nodes)
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        val result = scheduler.execute(
            targetNode = c,
            context = context,
            checkTopology = { true }
        )

        assertTrue(result is ExecutionResult.Success)

        // Verify that A and B ran concurrently (their start times are close, and A started before B finished)
        assertTrue(aStartedAt > 0)
        assertTrue(bStartedAt > 0)
        val startTimeDiff = Math.abs(aStartedAt - bStartedAt)
        assertTrue(startTimeDiff < 100) // Concurrently scheduled within 100ms

        // Verify C ran after both A and B finished
        assertTrue(cStartedAt >= aFinishedAt)
        assertTrue(cStartedAt >= bFinishedAt)
    }

    @Test
    fun testFailFastCancelation() = runBlocking {
        var bRan = false

        val a = task<ScriptedTask>("A") {
            action {
                throw RuntimeException("Task A fails immediately")
            }
        }

        val b = task<ScriptedTask>("B") {
            action {
                delay(200)
                bRan = true
                Unit
            }
        }

        val c = task<ScriptedTask>("C") {
            dependsOn(a, b)
            action {}
        }

        val scheduler = ConcurrentScheduler(TaskRegistry.nodes)
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        val result = scheduler.execute(
            targetNode = c,
            context = context,
            checkTopology = { true }
        )

        assertTrue(result is ExecutionResult.Failure)
        // Verify that B did not successfully run to completion because A failed immediately
        // and triggered cancellation.
        assertTrue(!bRan)
    }

    @Test
    fun testUncaughtCustomTaskExceptionFailsPipeline() = runBlocking {
        val task = object : Task<NoInputs, NoOutputs>("throws") {
            override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
            override fun buildOutputs() = NoOutputs()
            override suspend fun execute(context: ExecutionContext, inputSet: NoInputs): ExecutionResult<NoOutputs> {
                throw IllegalStateException("uncaught task failure")
            }
        }
        val scheduler = ConcurrentScheduler(mapOf(task.id to task))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = Worker(WorkerId("test-worker"))
        )

        val result = scheduler.execute(task, context, { true })

        assertTrue(result is ExecutionResult.Failure)
        val failure = result as ExecutionResult.Failure<*>
        assertEquals(1, failure.exitCode)
        assertEquals("uncaught task failure", failure.error.message)
    }
}
