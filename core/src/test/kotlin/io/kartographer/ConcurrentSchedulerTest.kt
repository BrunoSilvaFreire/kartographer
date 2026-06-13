package io.kartographer

import io.kartographer.core.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry
import io.kartographer.tasks.DefaultTask
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

        val a = task<DefaultTask>("A") {
            action {
                aStartedAt = System.currentTimeMillis()
                delay(150)
                aFinishedAt = System.currentTimeMillis()
            }
        }

        val b = task<DefaultTask>("B") {
            action {
                bStartedAt = System.currentTimeMillis()
                delay(150)
                bFinishedAt = System.currentTimeMillis()
            }
        }

        val c = task<DefaultTask>("C") {
            dependsOn(a, b)
            action {
                cStartedAt = System.currentTimeMillis()
            }
        }

        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        val result = scheduler.execute(
            targetTask = c,
            context = context,
            checkTopology = { true },
            onTaskStart = {},
            onTaskSuccess = {},
            onTaskFailure = { _, _, _ -> }
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

        val a = task<DefaultTask>("A") {
            action {
                throw RuntimeException("Task A fails immediately")
            }
        }

        val b = task<DefaultTask>("B") {
            action {
                delay(200)
                bRan = true
            }
        }

        val c = task<DefaultTask>("C") {
            dependsOn(a, b)
            action {}
        }

        val scheduler = ConcurrentScheduler(TaskRegistry.tasks)
        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        val result = scheduler.execute(
            targetTask = c,
            context = context,
            checkTopology = { true },
            onTaskStart = {},
            onTaskSuccess = {},
            onTaskFailure = { _, _, _ -> }
        )

        assertTrue(result is ExecutionResult.Failure)
        // Verify that B did not successfully run to completion because A failed immediately
        // and triggered cancellation.
        assertTrue(!bRan)
    }
}
