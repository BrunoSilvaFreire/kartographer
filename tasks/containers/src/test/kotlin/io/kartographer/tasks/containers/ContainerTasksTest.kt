package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.TaskId
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.core.model.Group
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.core.scheduler.MaterializedInputs
import io.kartographer.core.scheduler.TaskStateStore
import io.kartographer.dsl.NodeRegistry
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.group
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun configuredBuildInvocationSkipsUsingItsMaterializedInputState() = runBlocking {
        val projectDir = java.nio.file.Files.createTempDirectory("container-state")
        java.nio.file.Files.writeString(projectDir.resolve("Containerfile"), "FROM scratch\n")
        java.nio.file.Files.createDirectories(projectDir.resolve("context"))
        java.nio.file.Files.writeString(projectDir.resolve("context/input.txt"), "one\n")
        java.nio.file.Files.createDirectories(projectDir.resolve("configured-context"))
        java.nio.file.Files.writeString(projectDir.resolve("configured-context/input.txt"), "one\n")
        git(projectDir, "init", "-q")
        git(projectDir, "config", "user.email", "test@example.com")
        git(projectDir, "config", "user.name", "Test")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-qm", "initial")
        val context = ExecutionContext(projectDir = projectDir, worker = Worker(WorkerId("test-worker")))
        var executions = 0
        val build = object : ContainerBuild("build-state") {
            override fun localImageExists(context: ExecutionContext, ref: String) = ref == "registry.example/image:release"
            override suspend fun execute(context: ExecutionContext, inputSet: ContainerBuildInputs): ExecutionResult<ContainerBuildOutputs> {
                executions++
                return success {
                    imageRef.set("${inputSet.imageName}:${inputSet.version}")
                    this.sourceFingerprint.set(buildSourceFingerprint(context.projectDir, inputSet.containerfile, inputSet.context))
                    containerfilePath.set(inputSet.containerfile)
                    contextPath.set(inputSet.context)
                }
            }
        }.apply {
            imageName.set("registry.example/image")
            containerfile.set("Containerfile")
            this.context.set("context")
            version.set("registered")
            buildArgs.set(mapOf("PROJECT_VERSION" to "old", "GIT_COMMIT" to "ignored", "FOO" to "old"))
        }
        var effectiveBuildArgs = mapOf("PROJECT_VERSION" to "release", "FOO" to "old")
        val target = Group("target").apply {
            dependsOn(build) {
                it.version = "release"
                it.context = "configured-context"
                it.buildArgs = effectiveBuildArgs
            }
        }
        val scheduler = ConcurrentScheduler(mapOf(build.id to build, target.id to target))
        val materialize: suspend (io.kartographer.core.model.Task<*, *>, ExecutionContext) -> MaterializedInputs = { task, taskContext ->
            MaterializedInputs(task.buildInputsAny(taskContext))
        }

        scheduler.execute(target, context, { true }, materialize)
        scheduler.execute(target, context, { true }, materialize)
        assertEquals(1, executions)

        java.nio.file.Files.writeString(projectDir.resolve("Containerfile"), "FROM busybox\n")
        scheduler.execute(target, context, { true }, materialize)
        assertEquals(2, executions)

        java.nio.file.Files.writeString(projectDir.resolve("configured-context/input.txt"), "two\n")
        scheduler.execute(target, context, { true }, materialize)
        assertEquals(3, executions)

        effectiveBuildArgs = mapOf("PROJECT_VERSION" to "release", "FOO" to "changed")
        scheduler.execute(target, context, { true }, materialize)
        assertEquals(4, executions)
        assertFalse(build.isUpToDate(context, dependencyRan = true))
    }

    @Test
    fun immutableMatrixTagGuardDoesNotInvalidateAnUnchangedBuild() = runBlocking {
        val projectDir = java.nio.file.Files.createTempDirectory("matrix-tag-guard")
        val context = ExecutionContext(projectDir = projectDir, worker = Worker(WorkerId("test-worker")))
        val registry = NodeRegistry()
        var executions = 0
        val target = TaskRegistry.withRegistry(registry) {
            group("matrix") {
                containerMatrix(
                    images = listOf(ContainerImage("unused", "Containerfile")),
                    registry = "registry.example", namespace = "project", releaseTag = "release",
                    client = ContainerClient.NERDCTL, platforms = emptyList(), requireImmutableTag = true,
                )
            }
            val guard = registry.nodes.getValue(TaskId("matrix/validate_release_tag"))
            val build = object : ContainerBuild("probe-build") {
                override fun localImageExists(context: ExecutionContext, ref: String) = ref == "registry.example/probe:release"
                override suspend fun execute(context: ExecutionContext, inputSet: ContainerBuildInputs): ExecutionResult<ContainerBuildOutputs> {
                    executions++
                    return success {
                        imageRef.set("registry.example/probe:release")
                        sourceFingerprint.set(buildSourceFingerprint(context.projectDir, inputSet.containerfile, inputSet.context))
                        containerfilePath.set(inputSet.containerfile)
                        contextPath.set(inputSet.context)
                    }
                }
            }.apply {
                imageName.set("registry.example/probe")
                containerfile.set("Containerfile")
                version.set("release")
                dependsOn(guard)
            }
            registry.register(build)
            Group("target").apply {
                dependsOn(build)
                registry.register(this)
            }
        }
        val scheduler = ConcurrentScheduler(registry.nodes)

        scheduler.execute(target, context, { true })
        scheduler.execute(target, context, { true })

        assertEquals(1, executions)
    }

    @Test
    fun pushSkipsOnlyWhenDigestsMatch() = runBlocking {
        val context = ExecutionContext(projectDir = Paths.get("").toAbsolutePath(), worker = Worker(WorkerId("test-worker")))
        val matches = object : ContainerPush("push-state") {
            override fun registryDigestMatches(fullRef: String) = fullRef == "registry.example/image:release"
        }.apply { imageRef.set("registry.example/image:release"); registry.set("") }
        val inputs = ContainerPushInputs("registry.example/image:release", "", ContainerClient.NERDCTL, "k8s.io")
        val invocationContext = context.copy(inputHash = io.kartographer.core.model.InputSetCodec.hash(inputs))
        TaskStateStore.saveState(invocationContext, matches, ContainerPushOutputs().apply { pushedRef.set(inputs.imageRef) })
        assertTrue(matches.isUpToDate(invocationContext))
        val fails = object : ContainerPush("push-failure") {
            override fun registryDigestMatches(fullRef: String) = false
        }.apply { imageRef.set("registry.example/image:release"); registry.set("") }
        assertFalse(fails.isUpToDate(invocationContext))
    }

    @Test
    fun matrixPushMaterializesItsProviderBackedImageReference() = runBlocking {
        val registry = NodeRegistry()
        val push = TaskRegistry.withRegistry(registry) {
            group("containers") {
                containerMatrix(
                    images = listOf(ContainerImage("image", "Containerfile")),
                    registry = "registry.example", namespace = "project", releaseTag = "registered",
                    client = ContainerClient.NERDCTL, platforms = emptyList(),
                )
            }
            registry.nodes.getValue(TaskId("containers/push/image")) as ContainerPush
        }
        assertEquals(null, push.imageRef.directOrNull())
        val outputId = push.imageRef.producingInvocation!!.id
        val context = ExecutionContext(projectDir = Paths.get("").toAbsolutePath(), worker = Worker(WorkerId("test-worker")))
        val inputs = withContext(ProviderResolution(context, mapOf(outputId to mapOf("image" to "registry.example/project/image:release")))) {
            push.buildInputs(context)
        }
        assertEquals("registry.example/project/image:release", inputs.imageRef)
    }

    @Test
    fun registryDescriptorDigestUsesTheReportedDescriptorAndRejectsInvalidOutput() {
        val digest = "sha256:" + "a".repeat(64)
        assertEquals(digest, remoteDescriptorDigest("""{"Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"$digest"}}"""))
        assertEquals(null, remoteDescriptorDigest("""{"manifests":[{"digest":"$digest"}]}"""))
        assertEquals(null, remoteDescriptorDigest("manifest inspect failed"))
    }

    @Test
    fun pushDigestCheckRequiresEqualLocalAndRegistryDescriptorDigests() {
        val digest = "sha256:" + "a".repeat(64)
        val inputs = ContainerPushInputs("image:release", "registry.example", ContainerClient.NERDCTL, "k8s.io")
        fun check(local: String?, remote: String?) = object : ContainerPush("push-digest") {
            override fun localImageDigest(client: ContainerClient, namespace: String?, ref: String) = local
            override fun remoteManifestDigest(client: ContainerClient, namespace: String?, ref: String) = remote
            fun matches() = registryDigestMatches("registry.example/${inputs.imageRef}")
        }.matches()

        assertTrue(check(digest, digest))
        assertFalse(check(digest, "sha256:" + "b".repeat(64)))
        assertFalse(check(digest, null))
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

    @Test
    fun sourceFingerprintCoversContextChangesAtTheRepositoryRoot() {
        val repo = java.nio.file.Files.createTempDirectory("karto-fingerprint")
        try {
            git(repo, "init", "-q")
            git(repo, "config", "user.email", "test@example.com")
            git(repo, "config", "user.name", "Test")
            java.nio.file.Files.writeString(repo.resolve("Containerfile"), "FROM scratch\n")
            java.nio.file.Files.writeString(repo.resolve("module.py"), "VALUE = 1\n")
            git(repo, "add", ".")
            git(repo, "commit", "-q", "-m", "initial")

            val before = buildSourceFingerprint(repo, "Containerfile", ".")
            // A context equal to the repository root relativizes to "", and an empty
            // pathspec makes `git diff` fail — the fingerprint used to silently drop the
            // context hash entirely (leaving only the Containerfile hash before the colon).
            assertEquals(64, before.substringAfter(":").length)

            java.nio.file.Files.writeString(repo.resolve("module.py"), "VALUE = 2\n")
            val after = buildSourceFingerprint(repo, "Containerfile", ".")

            assertNotEquals(before, after)
        } finally {
            repo.toFile().deleteRecursively()
        }
    }

    private fun git(directory: java.nio.file.Path, vararg args: String) {
        val exit = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).start().waitFor()
        check(exit == 0)
    }
}
