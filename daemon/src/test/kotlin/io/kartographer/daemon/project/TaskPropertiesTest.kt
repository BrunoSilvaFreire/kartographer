package io.kartographer.daemon.project

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.NoInputs
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskPropertiesTest {

    private class GatedTask(name: String) : Task<NoInputs, NoOutputs>(name) {
        val confirm = property<String> { set("") }.withCLI("confirm", "Confirmation token")
        override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
        override fun buildOutputs() = NoOutputs()
        override suspend fun execute(context: ExecutionContext, inputSet: NoInputs) = success()
    }

    @Test
    fun `cli values are visible to execute and restored afterwards`() = runBlocking {
        val task = GatedTask("migrate_up")

        val seen = TaskProperties.withCliValues(listOf(task), mapOf("confirm" to "b7e4dfb6b6fb2df0")) {
            task.confirm.get()
        }

        assertEquals("b7e4dfb6b6fb2df0", seen)
        // The daemon reuses the task instance, so the value must not stick to the next run.
        assertEquals("", task.confirm.get())
    }
}
