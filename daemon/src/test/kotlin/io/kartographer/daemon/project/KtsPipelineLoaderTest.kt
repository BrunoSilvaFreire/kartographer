package io.kartographer.daemon.project

import io.kartographer.core.model.TaskId
import io.kartographer.core.plugins.PluginLoader
import io.kartographer.dsl.NodeRegistry
import io.kartographer.dsl.TaskRegistry
import io.kartographer.testing.ScriptedTask
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

class KtsPipelineLoaderTest {

    @Test
    fun `loads a script into the supplied registry`() {
        val scriptContent = """
            import io.kartographer.testing.ScriptedTask

            task<ScriptedTask>("compile") {
                inputs.dir("src")
                action { context ->
                    context.executeBash {
                        command("echo")
                        args("'Compiling'")
                    }
                }
            }
        """.trimIndent()

        val tempScript = Files.createTempFile("test_pipeline", ".kartographer.kts")
        try {
            tempScript.toFile().writeText(scriptContent)

            val registry = NodeRegistry()
            val pluginSet = PluginLoader.createProjectSet()
            val tasks = try {
                KtsPipelineLoader().load(tempScript.toAbsolutePath().toString(), registry, pluginSet)
            } catch (e: Exception) {
                println("COMPILATION ERROR DETAILS:")
                println(e.message)
                throw e
            }

            assertEquals(1, tasks.size)
            val task = tasks.values.first()
            assertTrue(task is ScriptedTask)
            assertEquals("compile", task.name)
            assertEquals("src", (task as ScriptedTask).inputs.dirs.first())

            // The whole point of the registry argument: the process-wide default is untouched,
            // so a second project loading in this JVM starts from a clean slate.
            assertFalse(TaskRegistry.nodes.containsKey(TaskId("compile")))
        } finally {
            tempScript.deleteIfExists()
        }
    }
}
