package io.kartographer.tasks.dart

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

private class Recorder : ExecutionEnvironment {
    val commands = mutableListOf<String>()
    val workingDirs = mutableListOf<String>()
    val environments = mutableListOf<Map<String, String>>()

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
    ): Int {
        commands.add(command)
        workingDirs.add(workingDir)
        environments.add(environment)
        return 0
    }
}

private fun contextFor(recorder: Recorder) = ExecutionContext(
    projectDir = Paths.get("").toAbsolutePath(),
    worker = Worker(WorkerId("test-worker")),
    executionEnvironment = recorder,
)

class DartTasksTest {

    @Test
    fun `run puts program arguments after the entrypoint`() = runBlocking {
        val recorder = Recorder()
        val task = DartRun("run").apply {
            target.set("bin/evaluator.dart")
            arg("--dry-run", "--log-level", "FINE")
        }

        task.execute(contextFor(recorder))

        // `dart run --dry-run bin/evaluator.dart` would hand the flag to the
        // Dart tool, which does not know it.
        assertEquals(
            "dart run bin/evaluator.dart --dry-run --log-level FINE",
            recorder.commands.single(),
        )
    }

    @Test
    fun `run resolves packageDir against the project root`() = runBlocking {
        val recorder = Recorder()
        val task = DartRun("run").apply {
            target.set("bin/evaluator.dart")
            packageDir.set("packages/evaluator")
        }

        task.execute(contextFor(recorder))

        assertTrue(
            recorder.workingDirs.single().replace('\\', '/').endsWith("/packages/evaluator"),
            "expected the package directory, got ${recorder.workingDirs.single()}",
        )
    }

    @Test
    fun `run passes its environment to the child process`() = runBlocking {
        val recorder = Recorder()
        val task = DartRun("run").apply {
            target.set("bin/evaluator.dart")
            environment("CITYSCAPE_CONFIG", "config/prod.yaml")
        }

        task.execute(contextFor(recorder))

        assertEquals("config/prod.yaml", recorder.environments.single()["CITYSCAPE_CONFIG"])
    }

    @Test
    fun `arguments carrying spaces survive the shell`() = runBlocking {
        val recorder = Recorder()
        val task = DartRun("run").apply {
            target.set("bin/evaluator.dart")
            arg("--project", "a b")
        }

        task.execute(contextFor(recorder))

        assertEquals(
            "dart run bin/evaluator.dart --project 'a b'",
            recorder.commands.single(),
        )
    }

    @Test
    fun `a named target replaces the configured test set`() = runBlocking {
        val recorder = Recorder()
        val task = DartTest("test").apply {
            target("test/a_test.dart", "test/b_test.dart")
            // What `--target` on the command line sets.
            target.set("test/c_test.dart")
        }

        task.execute(contextFor(recorder))

        assertEquals("dart test test/c_test.dart", recorder.commands.single())
    }

    @Test
    fun `test forwards the filters it was given and nothing else`() = runBlocking {
        val recorder = Recorder()
        val task = DartTest("test").apply {
            plainName.set("diff")
            reporter.set("compact")
        }

        task.execute(contextFor(recorder))

        assertEquals(
            "dart test --plain-name diff --reporter compact",
            recorder.commands.single(),
        )
    }

    @Test
    fun `test forwards tag filters`() = runBlocking {
        val recorder = Recorder()
        val task = DartTest("test").apply {
            tags.set("backend")
            excludeTags.set("slow")
        }

        task.execute(contextFor(recorder))

        assertEquals(
            "dart test --tags backend --exclude-tags slow",
            recorder.commands.single(),
        )
    }

    @Test
    fun `compile names its artifact as an output`() = runBlocking {
        val recorder = Recorder()
        val task = DartCompile("compile").apply {
            target.set("bin/evaluator.dart")
            output.set("build/evaluator")
        }

        val result = task.execute(contextFor(recorder))

        assertEquals(
            "dart compile exe bin/evaluator.dart -o build/evaluator",
            recorder.commands.single(),
        )
        assertEquals("build/evaluator", result.outputs.artifact.get())
    }

    @Test
    fun `test skips cleanly when target directory does not exist`() = runBlocking {
        val recorder = Recorder()
        val task = DartTest("test").apply {
            targets.set(listOf("nonexistent_test_dir"))
        }

        val result = task.execute(contextFor(recorder))

        assertTrue(result is ExecutionResult.Success)
        assertTrue(recorder.commands.isEmpty())
    }

    @Test
    fun `test appends coverage flag when coverage is enabled`() = runBlocking {
        val recorder = Recorder()
        val task = DartTest("test").apply {
            target.set("test/example_test.dart")
            coverage.set(true)
        }

        task.execute(contextFor(recorder))

        assertEquals("dart test --coverage=coverage test/example_test.dart", recorder.commands.single())
    }
}
