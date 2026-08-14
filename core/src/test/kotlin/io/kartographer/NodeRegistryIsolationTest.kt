package io.kartographer

import io.kartographer.core.model.TaskId
import io.kartographer.dsl.NodeRegistry
import io.kartographer.dsl.ProjectConfig
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.project
import io.kartographer.dsl.task
import io.kartographer.tasks.DefaultTask
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The daemon holds one [NodeRegistry] per cached project in a single JVM, so two pipelines
 * loaded concurrently must not see each other's nodes.
 */
class NodeRegistryIsolationTest {

    @BeforeEach
    fun setup() {
        TaskRegistry.clear()
    }

    @Test
    fun `dsl writes to the bound registry, not the default`() {
        val registry = NodeRegistry()

        TaskRegistry.withRegistry(registry) {
            task<DefaultTask>("scoped") { }
        }

        assertTrue(registry.nodes.containsKey(TaskId("scoped")))
        assertFalse(TaskRegistry.nodes.containsKey(TaskId("scoped")))
    }

    @Test
    fun `binding is restored after the block`() {
        val registry = NodeRegistry()

        TaskRegistry.withRegistry(registry) { }
        task<DefaultTask>("after") { }

        assertTrue(TaskRegistry.nodes.containsKey(TaskId("after")))
        assertTrue(registry.nodes.isEmpty())
    }

    @Test
    fun `project root is per registry`() {
        val a = NodeRegistry()
        val b = NodeRegistry()

        TaskRegistry.withRegistry(a) { project { rootDirectory = "/tmp/a" } }
        TaskRegistry.withRegistry(b) { project { rootDirectory = "/tmp/b" } }

        assertEquals("/tmp/a", a.projectRoot)
        assertEquals("/tmp/b", b.projectRoot)
        assertNull(ProjectConfig.projectRoot)
    }

    @Test
    fun `two projects loading on different threads stay separate`() {
        val first = NodeRegistry()
        val second = NodeRegistry()
        // Both threads are inside their block at the same time, so a shared backing store
        // would show up as cross-contamination rather than as a lucky interleaving.
        val bothBound = CountDownLatch(2)
        val failures = mutableListOf<Throwable>()

        val threads = listOf(first to "alpha", second to "beta").map { (registry, name) ->
            Thread {
                try {
                    TaskRegistry.withRegistry(registry) {
                        task<DefaultTask>(name) { }
                        bothBound.countDown()
                        check(bothBound.await(5, TimeUnit.SECONDS)) { "peer thread never bound" }
                        assertEquals(setOf(TaskId(name)), registry.nodes.keys)
                    }
                } catch (t: Throwable) {
                    synchronized(failures) { failures.add(t) }
                }
            }.apply { start() }
        }
        threads.forEach { it.join(10_000) }

        assertTrue(failures.isEmpty()) { "thread failures: $failures" }
        assertEquals(setOf(TaskId("alpha")), first.nodes.keys)
        assertEquals(setOf(TaskId("beta")), second.nodes.keys)
        assertTrue(TaskRegistry.nodes.isEmpty())
    }
}
