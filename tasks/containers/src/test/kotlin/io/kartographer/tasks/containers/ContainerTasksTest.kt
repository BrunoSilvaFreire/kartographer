package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ContainerTasksTest {

    @Test
    fun nerdctlPushPreservesEveryBuiltPlatform() = runBlocking {
        val capturedCommands = mutableListOf<String>()
        val environment = object : ExecutionEnvironment {
            override suspend fun execute(
                command: String,
                workingDir: String,
                environment: Map<String, String>,
                requiresElevation: Boolean,
            ): Int {
                capturedCommands.add(command)
                return 0
            }
        }
        val task = ContainerPush("push-multiarch").apply {
            imageRef.set("registry.example/image:release")
            registry.set("")
            containerClient.set(ContainerClient.NERDCTL)
            namespace.set("k8s.io")
        }
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = environment,
        )

        task.execute(context)

        assertEquals(
            listOf(
                "nerdctl --namespace k8s.io tag registry.example/image:release registry.example/image:release",
                "nerdctl --namespace k8s.io push --all-platforms registry.example/image:release",
            ),
            capturedCommands,
        )
    }
}
