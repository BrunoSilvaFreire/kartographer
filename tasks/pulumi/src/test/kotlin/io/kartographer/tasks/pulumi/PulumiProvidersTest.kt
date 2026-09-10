package io.kartographer.tasks.pulumi

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

private class Recorder(private val stdout: String = "") : ExecutionEnvironment {
    val commands = mutableListOf<String>()

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?,
    ): Int {
        commands.add(command)
        if (stdout.isNotEmpty()) onStdoutLine?.invoke(stdout)
        return 0
    }

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
    ): Int = execute(command, workingDir, environment, requiresElevation, null, null)
}

private fun scope(recorder: Recorder) = ProviderResolution(
    ExecutionContext(
        projectDir = Paths.get("").toAbsolutePath(),
        worker = Worker(WorkerId("test-worker")),
        executionEnvironment = recorder,
    ),
    producedOutputs = emptyMap(),
)

class PulumiProvidersTest {

    @Test
    fun `a stack output is read for the named stack and directory`() = runBlocking {
        val recorder = Recorder(stdout = "cityscape-backend\n")

        val value = withContext(scope(recorder)) {
            fromPulumiOutput("/repo/pulumi", "prod", "backendServiceName").get()
        }

        assertEquals("cityscape-backend", value)
        assertEquals(
            "pulumi stack output backendServiceName --stack prod --cwd /repo/pulumi",
            recorder.commands.single(),
        )
    }

    @Test
    fun `a plain stack output contributes to task state`() {
        // The point of reading one: when it changes, whatever consumes it should
        // run again.
        val provider = fromPulumiOutput("/repo/pulumi", "prod", "endpoint")
        assertFalse(provider.secret)
        assertTrue(provider.contributesToState)
    }

    @Test
    fun `a secret output asks pulumi to show it and stays out of state`() = runBlocking {
        val recorder = Recorder(stdout = "s3cret")
        val provider = fromPulumiOutput("/repo/pulumi", "prod", "dbPassword", secret = true)

        withContext(scope(recorder)) { provider.get() }

        assertTrue(recorder.commands.single().endsWith("--show-secrets"))
        assertTrue(provider.secret)
        assertFalse(provider.contributesToState)
    }

    @Test
    fun `config is secret by default, which is what an encrypted stack config is for`() = runBlocking {
        val recorder = Recorder(stdout = "pg-password")
        val provider = fromPulumiConfig("/repo/pulumi", "prod", "postgres-password")

        val value = withContext(scope(recorder)) { provider.get() }

        assertEquals("pg-password", value)
        assertTrue(provider.secret)
        assertEquals(
            "pulumi config get postgres-password --stack prod --cwd /repo/pulumi",
            recorder.commands.single(),
        )
    }
}
