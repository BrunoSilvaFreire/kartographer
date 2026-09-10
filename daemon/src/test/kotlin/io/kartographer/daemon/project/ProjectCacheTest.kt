package io.kartographer.daemon.project

import io.kartographer.core.model.TaskId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectCacheTest {

    private fun writeScript(dir: File, vararg taskNames: String): File {
        val script = File(dir, "karto.kts")
        script.writeText(taskNames.joinToString("\n") { """task<ScriptedTask>("$it") { }""" })
        return script
    }

    @Test
    fun `serves the same instance while nothing changes`(@TempDir dir: File) = runBlocking {
        writeScript(dir, "build")
        val cache = ProjectCache()

        val first = cache.use(dir, null) { it }
        val second = cache.use(dir, null) { it }

        assertSame(first, second, "an unchanged project should not be recompiled")
        assertEquals(setOf(TaskId("build")), first.nodes.keys)
    }

    @Test
    fun `reloads when the pipeline script changes`(@TempDir dir: File) = runBlocking {
        val script = writeScript(dir, "build")
        val cache = ProjectCache()

        val first = cache.use(dir, null) { it }

        // Bump mtime explicitly: a same-second rewrite can otherwise land on the same
        // timestamp and make this pass for the wrong reason.
        script.writeText("""task<ScriptedTask>("build") { }""" + "\n" + """task<ScriptedTask>("test") { }""")
        script.setLastModified(script.lastModified() + 2000)

        val second = cache.use(dir, null) { it }

        assertNotSame(first, second)
        assertEquals(setOf(TaskId("build"), TaskId("test")), second.nodes.keys)
    }

    @Test
    fun `two projects in one cache stay isolated`(@TempDir root: File) = runBlocking {
        val alpha = File(root, "alpha").apply { mkdirs() }
        val beta = File(root, "beta").apply { mkdirs() }
        writeScript(alpha, "alpha-task")
        writeScript(beta, "beta-task")
        val cache = ProjectCache()

        // Interleaved rather than sequential — the registries are per-project only if the
        // DSL binding is per-load, and a sequential test would pass either way.
        val (alphaNodes, betaNodes) = coroutineScope {
            val a = async { cache.use(alpha, null) { it.nodes.keys.toSet() } }
            val b = async { cache.use(beta, null) { it.nodes.keys.toSet() } }
            a.await() to b.await()
        }

        assertEquals(setOf(TaskId("alpha-task")), alphaNodes)
        assertEquals(setOf(TaskId("beta-task")), betaNodes)
    }

    @Test
    fun `missing karto kts is reported clearly`(@TempDir dir: File) = runBlocking {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ProjectCache().use(dir, null) { it } }
        }
        assertTrue(error.message!!.contains("karto.kts"), "unexpected message: ${error.message}")
    }
}
