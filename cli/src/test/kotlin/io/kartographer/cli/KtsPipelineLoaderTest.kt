package io.kartographer.cli

import io.kartographer.cli.KtsPipelineLoader
import io.kartographer.tasks.DefaultTask
import io.kartographer.dsl.TaskRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

class KtsPipelineLoaderTest {

    @BeforeEach
    fun setup() {
        TaskRegistry.clear()
    }

    @Test
    fun testLoadSimpleKtsScript() {
        val scriptContent = """
            task<DefaultTask>("compile") {
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
            
            val loader = KtsPipelineLoader()
            val tasks = try {
                loader.load(tempScript.toAbsolutePath().toString())
            } catch (e: Exception) {
                println("COMPILATION ERROR DETAILS:")
                println(e.message)
                throw e
            }

            assertEquals(1, tasks.size)
            val node = tasks.values.first()
            assertTrue(node is DefaultTask)
            val task = node as DefaultTask
            assertEquals("compile", task.name)
            assertEquals("src", task.inputs.dirs.first())
        } finally {
            tempScript.deleteIfExists()
        }
    }
}
