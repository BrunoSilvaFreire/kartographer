package io.kartographer

import io.kartographer.core.*
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.DagScheduler
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ProducerTask(name: String) : Task(name) {
    val messageVal = "Hello from producer"
    val messageOutput: Provider<String> = output { messageVal }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        context.variables["producer_ran"] = true
        return ExecutionResult.Success
    }
}

class ConsumerTask(name: String) : Task(name) {
    val messageInput: Property<String> = property()

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val msg = messageInput.get()
        context.variables["received_message"] = msg
        return ExecutionResult.Success
    }
}

class PropertiesTest {

    @BeforeEach
    fun setup() {
        TaskRegistry.clear()
    }

    @Test
    fun testPropertyBindingAndImplicitDependency() {
        val producer = task<ProducerTask>("producer") { }
        val consumer = task<ConsumerTask>("consumer") {
            messageInput.setProvider(producer.messageOutput)
        }

        val scheduler = DagScheduler(TaskRegistry.nodes)
        val order = scheduler.getExecutionOrder()

        // Verify implicit dependency: producer must run before consumer
        assertEquals(2, order.size)
        assertEquals(producer.id, order[0].id)
        assertEquals(consumer.id, order[1].id)
    }

    @Test
    fun testPropertyResolutionAndVariableSharing() = runBlocking {
        val producer = task<ProducerTask>("producer") { }
        val consumer = task<ConsumerTask>("consumer") {
            messageInput.setProvider(producer.messageOutput)
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        // Execute task 1 (producer)
        val result1 = producer.execute(context)
        assertTrue(result1 is ExecutionResult.Success)

        // Execute task 2 (consumer)
        val result2 = consumer.execute(context)
        assertTrue(result2 is ExecutionResult.Success)

        // Assert shared context state and property values
        assertEquals(true, context.variables["producer_ran"])
        assertEquals("Hello from producer", context.variables["received_message"])
    }

    @Test
    fun testWithCliDsl() = runBlocking {
        val prop = Property<String>().withCLI("version", "Override version") {
            completeWith("1.0", "2.0")
        }

        assertEquals("version", prop.cliName)
        assertEquals("Override version", prop.cliDescription)
        
        val completions = prop.completionProvider?.invoke()
        assertEquals(listOf("1.0", "2.0"), completions)
    }
}
