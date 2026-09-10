package io.kartographer

import io.kartographer.core.Property
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.core.scheduler.MaterializedInputs
import io.kartographer.dsl.NodeRegistry
import io.kartographer.testing.ScriptedTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private data class InvocationInputs(
    var value: String,
    var items: List<String> = emptyList(),
    var attributes: Map<String, Int> = emptyMap()
) : InputSet()

private class InvocationOutputs : OutputSet() { val value = output<String>("value") }

private class InvocationTask(name: String) : Task<InvocationInputs, InvocationOutputs>(name) {
    var executions = AtomicInteger()
    var active = AtomicInteger()
    var maxActive = AtomicInteger()
    override suspend fun buildInputs(context: ExecutionContext) = InvocationInputs("default")
    override fun buildOutputs() = InvocationOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: InvocationInputs): ExecutionResult<InvocationOutputs> {
        executions.incrementAndGet()
        val now = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, now) }
        delay(50)
        active.decrementAndGet()
        return success { value.set(inputSet.value) }
    }
}

private data class PairInputs(var first: String, var second: String) : InputSet()
private class PairConsumer(name: String) : Task<PairInputs, NoOutputs>(name) {
    val first: Property<String> = property()
    val second: Property<String> = property()
    override suspend fun buildInputs(context: ExecutionContext) = PairInputs(first.get(), second.get())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: PairInputs) = success {
        context.variables["pair"] = "${inputSet.first},${inputSet.second}"
    }
}

private class NullableOutputs : OutputSet() { val value = output<String?>("value") }
private class NullableProducer(name: String) : Task<NoInputs, NullableOutputs>(name) {
    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = NullableOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs) = success { value.set(null) }
}

private data class NullableInputs(var value: String?) : InputSet()
private class NullableConsumer(name: String) : Task<NullableInputs, NoOutputs>(name) {
    val value: Property<String?> = property()
    override suspend fun buildInputs(context: ExecutionContext) = NullableInputs(value.get())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NullableInputs) = success {
        context.variables["received-null"] = inputSet.value == null
    }
}

class TypedInvocationTest {
    @Test fun `input hashes are canonical and reject arbitrary values`() {
        val left = InvocationInputs("x", listOf("b", "a"), linkedMapOf("z" to 2, "a" to 1))
        val right = InvocationInputs("x", listOf("b", "a"), linkedMapOf("a" to 1, "z" to 2))
        assertEquals(InputSetCodec.hash(left), InputSetCodec.hash(right))

        class InvalidInputs(var callback: () -> Unit) : InputSet()
        assertThrows(IllegalArgumentException::class.java) { InputSetCodec.hash(InvalidInputs {}) }
    }

    @Test fun `configured invocations overlap and deliver exact typed outputs`() = runBlocking {
        val registry = NodeRegistry()
        val prerequisiteRuns = AtomicInteger()
        val prerequisite = ScriptedTask("prepare").apply {
            action { prerequisiteRuns.incrementAndGet() }
        }
        val producer = InvocationTask("build").apply { dependsOn(prerequisite) }
        val owner = Group("fanout")
        val first = owner.dependsOn(producer) { it.value = "arm64" }
        val second = owner.dependsOn(producer) { it.value = "amd64" }
        val consumer = PairConsumer("push").apply {
            this.first.setProvider(first.value)
            this.second.setProvider(second.value)
        }
        listOf(prerequisite, producer, owner, consumer).forEach(registry::register)

        val context = ExecutionContext(Files.createTempDirectory("typed-invocations"), worker = Worker(WorkerId("test")))
        val result = ConcurrentScheduler(registry.nodes).execute(consumer, context, checkTopology = { true })

        assertTrue(result is ExecutionResult.Success<*>)
        assertEquals(2, producer.executions.get())
        assertEquals(2, producer.maxActive.get())
        assertEquals(1, prerequisiteRuns.get())
        assertEquals("arm64,amd64", context.variables["pair"])
    }

    @Test fun `equal configured edges are not coalesced`() = runBlocking {
        val registry = NodeRegistry()
        val producer = InvocationTask("build")
        val owner = Group("fanout").apply {
            dependsOn(producer) { it.value = "same" }
            dependsOn(producer) { it.value = "same" }
        }
        listOf(producer, owner).forEach(registry::register)
        val context = ExecutionContext(Files.createTempDirectory("equal-invocations"), worker = Worker(WorkerId("test")))
        ConcurrentScheduler(registry.nodes).execute(owner, context, checkTopology = { true })
        assertEquals(2, producer.executions.get())
    }

    @Test fun `nullable typed outputs remain distinct from unset outputs`() = runBlocking {
        val registry = NodeRegistry()
        val producer = NullableProducer("nullable")
        val consumer = NullableConsumer("consumer").apply { value.setProvider(producer.outputs.value) }
        listOf(producer, consumer).forEach(registry::register)
        val context = ExecutionContext(Files.createTempDirectory("nullable-output"), worker = Worker(WorkerId("test")))

        val result = ConcurrentScheduler(registry.nodes).execute(consumer, context, checkTopology = { true })

        assertTrue(result is ExecutionResult.Success<*>)
        assertEquals(true, context.variables["received-null"])
    }

    @Test fun `configured input conflicting with CLI fails before execution`() = runBlocking {
        val registry = NodeRegistry()
        val producer = InvocationTask("scrape")
        val owner = Group("proxy").apply { dependsOn(producer) { it.value = "edge" } }
        listOf(producer, owner).forEach(registry::register)
        val context = ExecutionContext(Files.createTempDirectory("cli-conflict"), worker = Worker(WorkerId("test")))

        val result = ConcurrentScheduler(registry.nodes).execute(
            owner,
            context,
            checkTopology = { true },
            materializeInputs = { task, taskContext ->
                val inputs = task.buildInputsAny(taskContext)
                MaterializedInputs(inputs, mapOf("value" to "egress"), InputSetCodec.fields(inputs))
            }
        )

        assertTrue(result is ExecutionResult.Failure<*>)
        assertTrue((result as ExecutionResult.Failure<*>).error.message!!.contains("--egress"))
        assertEquals(0, producer.executions.get())
    }
}
