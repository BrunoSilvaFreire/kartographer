package io.kartographer

import io.kartographer.core.*
import io.kartographer.core.model.*
import io.kartographer.core.plugins.TaskSerializer
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SerializerTestTask(name: String) : Task<NoInputs, NoOutputs>(name) {
    var stringProp: String = ""
    var intProp: Int = 0
    var boolProp: Boolean = false
    val stringListProp = mutableListOf<String>()
    val wrappedProp: Property<String> = property()

    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs) = success()
}

class TaskSerializerTest {

    @Test
    fun testSerializationAndDeserialization() {
        // 1. Arrange a populated task
        val task = SerializerTestTask("test-task").apply {
            stringProp = "Hello World"
            intProp = 42
            boolProp = true
            stringListProp.addAll(listOf("apple", "banana", "cherry"))
            wrappedProp.set("wrapped-value")
        }

        // 2. Act: Serialize
        val serialized = runBlocking { TaskSerializer.serializeProperties(task) }

        // 3. Assert serialized values
        assertEquals("Hello World", serialized["stringProp"])
        assertEquals("42", serialized["intProp"])
        assertEquals("true", serialized["boolProp"])
        assertEquals("apple,banana,cherry", serialized["stringListProp"])
        assertEquals("wrapped-value", serialized["wrappedProp"])

        // 4. Arrange a clean task for reconstruction
        val cleanTask = SerializerTestTask("clean-task")

        // 5. Act: Deserialize
        TaskSerializer.deserializeProperties(cleanTask, serialized)

        // 6. Assert restored values
        assertEquals("Hello World", cleanTask.stringProp)
        assertEquals(42, cleanTask.intProp)
        assertTrue(cleanTask.boolProp)
        assertEquals(listOf("apple", "banana", "cherry"), cleanTask.stringListProp)
        assertEquals("wrapped-value", runBlocking { cleanTask.wrappedProp.get() })
    }
}
