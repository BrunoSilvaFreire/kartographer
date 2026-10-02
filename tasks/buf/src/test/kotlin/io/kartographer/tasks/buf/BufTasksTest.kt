package io.kartographer.tasks.buf

import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

suspend fun <I : InputSet, O : OutputSet> Task<I, O>.execute(context: ExecutionContext) =
    execute(context, buildInputs(context))

class BufTasksTest {

    private fun createContext(capturedCommands: MutableList<String>): ExecutionContext {
        val mockEnv = object : ExecutionEnvironment {
            override suspend fun execute(
                command: String,
                workingDir: String,
                environment: Map<String, String>,
                requiresElevation: Boolean
            ): Int {
                capturedCommands.add(command)
                return 0
            }
        }
        return ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockEnv
        )
    }

    @Test
    fun testBufGenerateDefault() = runBlocking {
        val commands = mutableListOf<String>()
        val context = createContext(commands)
        val task = BufGenerate("generate")

        task.execute(context)
        assertEquals(1, commands.size)
        assertEquals("buf generate", commands[0])
    }

    @Test
    fun testBufGenerateCustom() = runBlocking {
        val commands = mutableListOf<String>()
        val context = createContext(commands)
        val task = BufGenerate("generate").apply {
            bufBin = "/usr/local/bin/buf"
            args.addAll(listOf("--template", "buf.gen.yaml", "proto"))
        }

        task.execute(context)
        assertEquals(1, commands.size)
        assertEquals("/usr/local/bin/buf generate --template buf.gen.yaml proto", commands[0])
    }

    @Test
    fun testBufLint() = runBlocking {
        val commands = mutableListOf<String>()
        val context = createContext(commands)
        val task = BufLint("lint")

        task.execute(context)
        assertEquals(1, commands.size)
        assertEquals("buf lint", commands[0])
    }

    @Test
    fun testBufBreakingWithAgainst() = runBlocking {
        val commands = mutableListOf<String>()
        val context = createContext(commands)
        val task = BufBreaking("breaking").apply {
            against = ".git#branch=main"
        }

        task.execute(context)
        assertEquals(1, commands.size)
        assertEquals("buf breaking --against .git#branch=main", commands[0])
    }
}
