package io.kartographer

import io.kartographer.core.*
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.dsl.semaphore
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry
import io.kartographer.testing.ScriptedTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

class ServiceOutputSet : OutputSet() {
    val endpoint = output<String>("endpoint")
}

class OutputServiceTask(name: String) : Task<NoInputs, ServiceOutputSet>(name) {
    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = ServiceOutputSet()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs): ExecutionResult<ServiceOutputSet> {
        val outputs = buildOutputs()
        context.emitOutput(outputs.endpoint, "ready-value")
        context.markReady()
        delay(75)
        context.emitOutput(outputs.endpoint, "late-value")
        awaitCancellation()
    }
}

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

    /** A failing task must not take an unrelated in-flight task's work with it. */
    @Test
    fun testKeepGoingLetsSiblingsFinish() = runBlocking {
        var bRan = false
        var cRan = false

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
            action { cRan = true }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(
            targetNode = c,
            context = testContext(),
            checkTopology = { true }
        )

        assertTrue(result is ExecutionResult.Failure)
        // B is unrelated to the failure and had already started: its result stands.
        assertTrue(bRan)
        // C depends on the failure and must not run.
        assertTrue(!cRan)
    }

    @Test
    fun testFinalizerRunsAfterFailedDependencies() = runBlocking {
        var finalizerRan = false
        val failed = task<ScriptedTask>("failed") {
            action { throw RuntimeException("expected") }
        }
        val finalizer = task<ScriptedTask>("finalizer") {
            dependsOn(failed)
            runAfterFailedDependencies()
            action {
                finalizerRan = true
                throw RuntimeException("finalizer reports the failed run")
            }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(
            targetNode = finalizer,
            context = testContext(),
            checkTopology = { true }
        )

        assertTrue(finalizerRan)
        assertTrue(result is ExecutionResult.Failure)
        assertEquals("finalizer reports the failed run", (result as ExecutionResult.Failure<*>).error.message)
    }

    @Test
    fun testFailFastCancelsSiblings() = runBlocking {
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

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(
            targetNode = c,
            context = testContext(mapOf("KARTO_FAIL_FAST" to "1")),
            checkTopology = { true }
        )

        assertTrue(result is ExecutionResult.Failure)
        assertTrue(!bRan)
    }

    /** Two tasks naming the same semaphore never overlap, however parallel the run is. */
    @Test
    fun testSemaphoreSerializesHolders() = runBlocking {
        val assets = semaphore("assets")
        val overlaps = java.util.concurrent.atomic.AtomicInteger()
        val inside = java.util.concurrent.atomic.AtomicInteger()

        fun holder(name: String) = task<ScriptedTask>(name) {
            acquires(assets)
            action {
                if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
                delay(80)
                inside.decrementAndGet()
                Unit
            }
        }

        val a = holder("A")
        val b = holder("B")
        val all = task<ScriptedTask>("all") {
            dependsOn(a, b)
            action {}
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(all, testContext(), { true })

        assertTrue(result is ExecutionResult.Success)
        assertEquals(0, overlaps.get())
    }

    /**
     * Releasing early is the point of the feature: the holder keeps running while the next
     * task starts, so the two overlap even though the guarded phase did not.
     */
    @Test
    fun testEarlyReleaseLetsNextTaskStart() = runBlocking {
        val assets = semaphore("assets")
        val started = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var firstStillRunningWhenSecondStarted = false

        var firstDone = false
        val first = task<ScriptedTask>("first") {
            acquires(assets)
            action { context ->
                started.add("first")
                // Guarded phase over — hand the permit back and keep working.
                context.releaseSemaphore(assets)
                delay(200)
                firstDone = true
                Unit
            }
        }

        val second = task<ScriptedTask>("second") {
            acquires(assets)
            action {
                delay(40)
                started.add("second")
                firstStillRunningWhenSecondStarted = !firstDone
                Unit
            }
        }

        val all = task<ScriptedTask>("all") {
            dependsOn(first, second)
            action {}
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(all, testContext(), { true })

        assertTrue(result is ExecutionResult.Success)
        assertEquals(listOf("first", "second"), started.toList())
        assertTrue(firstStillRunningWhenSecondStarted)
    }

    @Test
    fun testJobLimitCapsConcurrency() = runBlocking {
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()

        val leaves = (1..4).map { index ->
            task<ScriptedTask>("leaf-$index") {
                action {
                    val now = inside.incrementAndGet()
                    peak.updateAndGet { high -> maxOf(high, now) }
                    delay(60)
                    inside.decrementAndGet()
                    Unit
                }
            }
        }
        val all = task<ScriptedTask>("all") {
            dependsOn(*leaves.toTypedArray())
            action {}
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes)
            .execute(all, testContext(mapOf("KARTO_JOBS" to "2")), { true })

        assertTrue(result is ExecutionResult.Success)
        assertTrue(peak.get() <= 2, "expected at most 2 concurrent tasks, saw ${peak.get()}")
    }

    @Test
    fun `service dependency starts consumer at readiness and stops service afterward`() = runBlocking {
        var serviceStopped = false
        var consumerRan = false
        val service = task<ScriptedTask>("service") {
            service { readiness = signal() }
            action { execution ->
                execution.markReady()
                try {
                    awaitCancellation()
                } finally {
                    serviceStopped = true
                }
            }
        }
        val consumer = task<ScriptedTask>("consumer") {
            dependsOn(service)
            action { consumerRan = true }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(consumer, testContext(), { true })

        assertTrue(result is ExecutionResult.Success)
        assertTrue(consumerRan)
        assertTrue(serviceStopped)
    }

    @Test
    fun `service readiness releases job slot for dependent service`() = runBlocking {
        val first = task<ScriptedTask>("first-service") {
            service { readiness = signal() }
            action { execution -> execution.markReady(); awaitCancellation() }
        }
        val second = task<ScriptedTask>("second-service") {
            dependsOn(first)
            service { readiness = signal() }
            action { execution -> execution.markReady(); awaitCancellation() }
        }
        val consumer = task<ScriptedTask>("consumer") {
            dependsOn(second)
            action { }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(
            consumer,
            testContext(mapOf("KARTO_JOBS" to "1")),
            { true }
        )

        assertTrue(result is ExecutionResult.Success)
    }

    @Test
    fun `completion dependency waits for an explicitly completing service`() = runBlocking {
        var consumerRan = false
        val service = task<ScriptedTask>("short-service") {
            service { readiness = signal() }
            action { execution ->
                execution.markReady()
                delay(40)
            }
        }
        val consumer = task<ScriptedTask>("consumer") {
            dependsOnCompletion(service)
            action { consumerRan = true }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(consumer, testContext(), { true })

        assertTrue(result is ExecutionResult.Success)
        assertTrue(consumerRan)
    }

    @Test
    fun `service readiness timeout fails the run`() = runBlocking {
        val service = task<ScriptedTask>("never-ready") {
            service {
                readiness = signal()
                timeout = 60.milliseconds
            }
            action { awaitCancellation() }
        }
        val consumer = task<ScriptedTask>("consumer") {
            dependsOn(service)
            action { }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(consumer, testContext(), { true })

        assertTrue(result is ExecutionResult.Failure)
        assertTrue((result as ExecutionResult.Failure<*>).error.message!!.contains("did not become ready"))
    }

    @Test
    fun `log readiness observes task output`() = runBlocking {
        var consumerRan = false
        val service = task<ScriptedTask>("logging-service") {
            service { readiness = log(Regex("listening")) }
            action { execution ->
                execution.observer!!.onEvent(
                    ExecutionEvent.TaskOutput(execution.executionNode!!, "server listening", false)
                )
                awaitCancellation()
            }
        }
        val consumer = task<ScriptedTask>("consumer") {
            dependsOn(service)
            action { consumerRan = true }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(consumer, testContext(), { true })

        assertTrue(result is ExecutionResult.Success)
        assertTrue(consumerRan)
    }

    @Test
    fun `outputs are snapshotted when service becomes ready`() = runBlocking {
        val service = task<OutputServiceTask>("output-service") {
            service { readiness = signal() }
        }
        var observed = ""
        val consumer = task<ScriptedTask>("consumer") {
            val outputs = dependsOn(service)
            action { observed = outputs.endpoint.get() }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(consumer, testContext(), { true })

        assertTrue(result is ExecutionResult.Success)
        assertEquals("ready-value", observed)
    }

    private fun testContext(environment: Map<String, String> = emptyMap()) = ExecutionContext(
        projectDir = Paths.get("").toAbsolutePath(),
        environment = environment,
        worker = Worker(WorkerId("test-worker"))
    )

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

    @Test
    fun `returned failure completes the pipeline`() = runBlocking {
        val failed = task<ScriptedTask>("failed") {
            action { ExecutionResult.Failure(RuntimeException("expected"), 7, NoOutputs()) }
        }

        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(failed, testContext(), { true })

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(7, (result as ExecutionResult.Failure<*>).exitCode)
    }
}
