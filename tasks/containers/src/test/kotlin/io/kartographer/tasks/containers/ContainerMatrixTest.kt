package io.kartographer.tasks.containers

import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.core.Property
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.dsl.NodeRegistry
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.group
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerMatrixTest {

    private val images = listOf(
        ContainerImage("dependencies", "containers/dependencies/Containerfile"),
        ContainerImage("playwright", "containers/playwright/Containerfile", deps = listOf("dependencies")),
        ContainerImage("vivarealapi", "sources/vivarealapi/Containerfile", deps = listOf("playwright")),
    )

    private fun build(
        requireImmutableTag: Boolean = false,
        releaseTag: String = "v1.2.3",
        images: List<ContainerImage> = this.images,
    ): NodeRegistry {
        val registry = NodeRegistry()
        TaskRegistry.withRegistry(registry) {
            group("prod") {
                group("containers") {
                    containerMatrix(
                        images = images,
                        registry = "harbor.lunari.studio",
                        namespace = "cityscape",
                        releaseTag = releaseTag,
                        client = ContainerClient.NERDCTL_ROOTFUL,
                        platforms = listOf("linux/amd64"),
                        requireImmutableTag = requireImmutableTag,
                    )
                }
            }
        }
        return registry
    }

    private fun NodeRegistry.deps(path: String): Set<String> =
        (nodes.getValue(TaskId(path)) as Task<*, *>).dependencyObjects.map { it.name }.toSet()

    private fun NodeRegistry.pushDeps(path: String): List<String> =
        (nodes.getValue(TaskId(path)) as Task<*, *>).invocationDependencyTasks().map { it.name }

    @Test
    fun `registers a build and a push per image under the env-first path`() {
        val registry = build()

        for (image in listOf("dependencies", "playwright", "vivarealapi")) {
            assertTrue(registry.nodes.containsKey(TaskId("prod/containers/build/$image")), "build/$image")
            assertTrue(registry.nodes.containsKey(TaskId("prod/containers/push/$image")), "push/$image")
        }
    }

    @Test
    fun `image deps become build dependencies`() {
        val registry = build()

        assertTrue("prod/containers/build/dependencies" in registry.deps("prod/containers/build/playwright"))
        assertTrue("prod/containers/build/playwright" in registry.deps("prod/containers/build/vivarealapi"))
        // Transitive only — not restated on the far end.
        assertFalse("prod/containers/build/dependencies" in registry.deps("prod/containers/build/vivarealapi"))
    }

    @Test
    fun `each push depends on its own build as a parametrized dependency`() {
        val registry = build()

        assertEquals(
            listOf("prod/containers/build/vivarealapi"),
            registry.pushDeps("prod/containers/push/vivarealapi"),
        )
    }

    @Test
    fun `push carries the version CLI property like build does`() {
        val registry = build()

        val push = registry.nodes.getValue(TaskId("prod/containers/push/vivarealapi")) as ContainerPush
        val cli = push.registeredProperties.mapNotNull { it.cliName }

        assertTrue("version" in cli, "push should expose --version; got: $cli")
    }

    @Test
    fun `the push group also exposes the version CLI property`() {
        val registry = build()

        val group = registry.nodes.getValue(TaskId("prod/containers/push"))
        val cli = group.registeredProperties.mapNotNull { it.cliName }

        assertTrue("version" in cli, "push group should expose --version; got: $cli")
    }

    @Test
    fun `the release tag guard is registered and gates every build only when required`() {
        val guarded = build(requireImmutableTag = true)
        val guard = "prod/containers/validate_release_tag"

        assertTrue(guarded.nodes.containsKey(TaskId(guard)))
        assertTrue(guard in guarded.deps("prod/containers/build/dependencies"))

        val unguarded = build(requireImmutableTag = false)
        assertFalse(unguarded.nodes.containsKey(TaskId(guard)))
    }

    @Test
    fun `the release tag guard validates the per-run version override`() = runBlocking {
        val registry = build(requireImmutableTag = true, releaseTag = "latest")
        @Suppress("UNCHECKED_CAST")
        val guard = registry.nodes.getValue(TaskId("prod/containers/validate_release_tag"))
            as Task<InputSet, OutputSet>
        @Suppress("UNCHECKED_CAST")
        (guard.registeredProperties.single { it.cliName == "version" } as Property<String>).set("1.1.0")
        val context = ExecutionContext(
            projectDir = java.nio.file.Paths.get("").toAbsolutePath(),
            worker = Worker(WorkerId("test-worker")),
        )

        assertTrue(guard.execute(context, guard.buildInputs(context)) is ExecutionResult.Success)
    }

    @Test
    fun `an image depending on one declared after it is rejected`() {
        val outOfOrder = listOf(
            ContainerImage("app", "Dockerfile", deps = listOf("base")),
            ContainerImage("base", "base/Dockerfile"),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            build(images = outOfOrder)
        }
        assertTrue(error.message!!.contains("'base'"), error.message)
    }
}
