package io.kartographer.tasks.containers

import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.dsl.NodeRegistry
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.group
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
                        platform = "linux/amd64",
                        requireImmutableTag = requireImmutableTag,
                    )
                }
            }
        }
        return registry
    }

    private fun NodeRegistry.deps(path: String): Set<String> =
        (nodes.getValue(TaskId(path)) as Task).dependencyObjects.map { it.name }.toSet()

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
    fun `each push depends on its own build`() {
        val registry = build()

        assertEquals(
            setOf("prod/containers/build/vivarealapi"),
            registry.deps("prod/containers/push/vivarealapi"),
        )
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
