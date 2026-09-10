package io.kartographer

import io.kartographer.core.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

private class TestOutputs : OutputSet() {
    val image = output<String>("image")
    val token = output<String>("token", secret = true)
    val digest = output<String>("digest")
}

private class OutputtingTask(name: String) : Task<NoInputs, TestOutputs>(name) {
    var failAfterImage = false
    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()
    override fun buildOutputs() = TestOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs): ExecutionResult<TestOutputs> {
        if (failAfterImage) return failure(RuntimeException("boom")) { image.set("my-app:1.0") }
        return success {
            image.set("my-app:1.0")
            token.set("hunter2")
            digest.set("sha256:my-app:1.0")
        }
    }
}

class TaskOutputsTest {
    @Test fun typedOutputsMaskSecrets() = kotlinx.coroutines.runBlocking {
        val result = OutputtingTask("build").execute(context()) as ExecutionResult.Success<*>
        assertEquals(
            mapOf("image" to "my-app:1.0", "token" to SECRET_MASK, "digest" to "sha256:my-app:1.0"),
            result.outputs.rendered()
        )
    }

    @Test fun partialOutputsSurviveFailure() = kotlinx.coroutines.runBlocking {
        val task = OutputtingTask("build").apply { failAfterImage = true }
        val result = task.execute(context()) as ExecutionResult.Failure<*>
        assertEquals(mapOf("image" to "my-app:1.0"), result.outputs.rendered())
    }

    @Test fun emitOutputSetsValueAndEmitsEvent() {
        val outputs = TestOutputs()
        val events = mutableListOf<ExecutionEvent>()
        val node = object : Node("build") {}
        val ctx = context().copy(executionNode = node, observer = object : ExecutionObserver {
            override fun onEvent(event: ExecutionEvent) { events.add(event) }
        })
        ctx.emitOutput(outputs.image, "dynamicValue")
        assertEquals("dynamicValue", runBlocking { outputs.image.get() })
        assertEquals("image", events.filterIsInstance<ExecutionEvent.TaskOutputProduced>().single().name)
    }

    private fun context() = ExecutionContext(
        java.nio.file.Paths.get("").toAbsolutePath(), worker = Worker(WorkerId("test-worker"))
    )
}
