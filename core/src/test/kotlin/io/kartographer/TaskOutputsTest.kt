package io.kartographer

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.SECRET_MASK
import io.kartographer.core.model.Task
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class OutputtingTask(name: String) : Task(name) {
    val image = property<String>().asOutput("image")
    val token = property<String>().asOutput("token", secret = true)
    val internal = property<String>()
    val digest = output("digest") { "sha256:${image.get()}" }

    var failAfterImage = false

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        image.set("my-app:1.0")
        if (failAfterImage) return ExecutionResult.Failure(RuntimeException("boom"))
        token.set("hunter2")
        internal.set("not-an-output")
        return ExecutionResult.Success
    }
}

class TaskOutputsTest {

    @Test
    fun collectsDeclaredOutputsAndMasksSecrets() = kotlinx.coroutines.runBlocking {
        val task = OutputtingTask("build")
        task.execute(context())

        assertEquals(
            mapOf("image" to "my-app:1.0", "token" to SECRET_MASK, "digest" to "sha256:my-app:1.0"),
            task.collectOutputs()
        )
    }

    @Test
    fun reportsNothingBeforeExecution() {
        // `digest` would throw on an unset `image`; collection must swallow that.
        assertEquals(emptyMap<String, String>(), OutputtingTask("build").collectOutputs())
    }

    @Test
    fun collectsWhatWasSetBeforeAFailure() = kotlinx.coroutines.runBlocking {
        val task = OutputtingTask("build").apply { failAfterImage = true }
        task.execute(context())

        assertEquals(
            mapOf("image" to "my-app:1.0", "digest" to "sha256:my-app:1.0"),
            task.collectOutputs()
        )
    }

    private fun context() = ExecutionContext(
        projectDir = java.nio.file.Paths.get("").toAbsolutePath(),
        worker = io.kartographer.core.model.Worker(io.kartographer.core.model.WorkerId("test-worker"))
    )
}
