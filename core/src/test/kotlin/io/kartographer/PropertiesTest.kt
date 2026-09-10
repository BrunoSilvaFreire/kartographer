package io.kartographer

import io.kartographer.core.Property
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.core.scheduler.DagScheduler
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.task
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ProducerOutputs : OutputSet() { val message = output<String>("message") }
class ProducerTask(name: String) : Task<NoInputs, ProducerOutputs>(name) {
    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = ProducerOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs) = success {
        context.variables["producer_ran"] = true
        message.set("Hello from producer")
    }
}

data class ConsumerInputs(var message: String) : InputSet()
class ConsumerTask(name: String) : Task<ConsumerInputs, NoOutputs>(name) {
    val messageInput: Property<String> = property()
    override suspend fun buildInputs(context: ExecutionContext) = ConsumerInputs(messageInput.get())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: ConsumerInputs) = success {
        context.variables["received_message"] = inputSet.message
    }
}

class PropertiesTest {
    @BeforeEach fun setup() = TaskRegistry.clear()

    @Test fun testPropertyBindingAndImplicitDependency() {
        val producer = task<ProducerTask>("producer") { }
        val consumer = task<ConsumerTask>("consumer") { messageInput.setProvider(producer.outputs.message) }
        val order = DagScheduler(TaskRegistry.nodes).getExecutionOrder()
        assertEquals(listOf(producer.id, consumer.id), order.map { it.id })
    }

    @Test fun testPropertyResolutionAndVariableSharing() = runBlocking {
        val producer = task<ProducerTask>("producer") { }
        val consumer = task<ConsumerTask>("consumer") { messageInput.setProvider(producer.outputs.message) }
        val context = ExecutionContext(Paths.get("").toAbsolutePath(), worker = Worker(WorkerId("test-worker")))
        val result = ConcurrentScheduler(TaskRegistry.nodes).execute(consumer, context, checkTopology = { true })
        assertTrue(result is ExecutionResult.Success<*>)
        assertEquals(true, context.variables["producer_ran"])
        assertEquals("Hello from producer", context.variables["received_message"])
    }

    @Test fun testWithCliDsl() = runBlocking {
        val prop = Property<String>().withCLI("version", "Override version") { completeWith("1.0", "2.0") }
        assertEquals("version", prop.cliName)
        assertEquals("Override version", prop.cliDescription)
        assertEquals(listOf("1.0", "2.0"), prop.completionProvider?.invoke())
    }
}
