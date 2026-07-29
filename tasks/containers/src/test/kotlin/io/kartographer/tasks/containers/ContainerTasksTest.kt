package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ContainerTasksTest {

    @Test
    fun containerBuildAndPushAlwaysRunBecauseTheirStateIsExternal() = runBlocking {
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = Worker(WorkerId("test-worker")),
        )
        val build = ContainerBuild("build").apply {
            imageName.set("registry.example/image")
            containerfile.set("Dockerfile")
        }
        val push = ContainerPush("push").apply {
            imageRef.set("registry.example/image:release")
            registry.set("")
        }

        assertFalse(build.isUpToDate(context))
        assertFalse(push.isUpToDate(context))
    }

    @Test
    fun nerdctlPushPreservesEveryBuiltPlatformWithoutRetaggingTheSameReference() = runBlocking {
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
                "nerdctl --namespace k8s.io push --all-platforms registry.example/image:release",
            ),
            capturedCommands,
        )
    }

    @Test
    fun nerdctlPushRetagsWhenTheRegistryChanges() = runBlocking {
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
        val task = ContainerPush("push-retagged").apply {
            imageRef.set("image:release")
            registry.set("registry.example")
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
                "nerdctl --namespace k8s.io tag image:release registry.example/image:release",
                "nerdctl --namespace k8s.io push --all-platforms registry.example/image:release",
            ),
            capturedCommands,
        )
    }

    @Test
    fun versionedBuildIncludesReleaseAndSourceMetadata() = runBlocking {
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
        val task = ContainerBuild("build-versioned").apply {
            imageName.set("registry.example/image")
            containerfile.set("Dockerfile")
            version.set("release")
            containerClient.set(ContainerClient.NERDCTL)
            namespace.set("k8s.io")
        }
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            environment = mapOf(
                "GIT_COMMIT" to "0123456789abcdef0123456789abcdef01234567",
                "BUILD_DATE" to "2026-07-29T17:00:00Z",
            ),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = environment,
        )

        task.execute(context)

        assertEquals(
            listOf(
                "nerdctl --namespace k8s.io build -t registry.example/image:release -f Dockerfile " +
                    "--build-arg VERSION=release " +
                    "--build-arg GIT_COMMIT=0123456789abcdef0123456789abcdef01234567 " +
                    "--build-arg BUILD_DATE=2026-07-29T17:00:00Z .",
            ),
            capturedCommands,
        )
    }
}
