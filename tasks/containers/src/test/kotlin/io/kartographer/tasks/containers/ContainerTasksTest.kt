package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.core.scheduler.TaskStateStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ContainerTasksTest {

    @Test
    fun containerBuildAndPushRerunUntilTheirExternalStateMatches() = runBlocking {
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
    fun buildUsesPropertiesAndImageExistenceForUpToDateChecks() = runBlocking {
        val context = ExecutionContext(projectDir = Paths.get("").toAbsolutePath(), worker = Worker(WorkerId("test-worker")))
        val build = object : ContainerBuild("build-state") {
            override fun localImageExists(context: ExecutionContext, ref: String) = true
        }.apply {
            imageName.set("registry.example/image")
            containerfile.set("tasks/containers/build.gradle.kts")
            version.set("release")
            buildArgs.set(mapOf("PROJECT_VERSION" to "old", "GIT_COMMIT" to "ignored", "FOO" to "old"))
        }
        TaskStateStore.saveState(context, build)
        assertTrue(build.isUpToDate(context))
        build.buildArgs.set(mapOf("PROJECT_VERSION" to "old", "FOO" to "changed"))
        assertFalse(build.isUpToDate(context))
        assertFalse(build.isUpToDate(context, dependencyRan = true))
    }

    @Test
    fun pushSkipsOnlyWhenDigestsMatch() = runBlocking {
        val context = ExecutionContext(projectDir = Paths.get("").toAbsolutePath(), worker = Worker(WorkerId("test-worker")))
        val matches = object : ContainerPush("push-state") {
            override fun registryDigestMatches() = true
        }.apply { imageRef.set("registry.example/image:release"); registry.set("") }
        assertTrue(matches.isUpToDate(context))
        val fails = object : ContainerPush("push-failure") {
            override fun registryDigestMatches() = false
        }.apply { imageRef.set("registry.example/image:release"); registry.set("") }
        assertFalse(fails.isUpToDate(context))
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
            buildArgs.set(mapOf("PROJECT_VERSION" to "latest"))
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
                    "--build-arg BUILD_DATE=2026-07-29T17:00:00Z " +
                    "--build-arg PROJECT_VERSION=release .",
            ),
            capturedCommands,
        )
    }

    @Test
    fun multiPlatformBuildEmitsOneFlagPerPlatform() = runBlocking {
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
        val task = ContainerBuild("build-multiarch").apply {
            imageName.set("registry.example/image")
            containerfile.set("Dockerfile")
            version.set("release")
            platforms.set(listOf("linux/amd64", "linux/arm64"))
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
                    "--build-arg BUILD_DATE=2026-07-29T17:00:00Z " +
                    "--platform linux/amd64 --platform linux/arm64 .",
            ),
            capturedCommands,
        )
    }
}
