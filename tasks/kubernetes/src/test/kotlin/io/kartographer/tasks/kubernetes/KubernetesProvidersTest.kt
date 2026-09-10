package io.kartographer.tasks.kubernetes

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Base64

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

class KubernetesProvidersTest {

    @Test
    fun `a secret is fetched as base64 and decoded here, with no shell pipe`() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString("hunter2".toByteArray())
        val recorder = Recorder(stdout = encoded)

        val value = withContext(scope(recorder)) {
            fromKubernetesSecret("cityscape-evaluator-oidc", "client-secret", namespace = "cityscape").get()
        }

        assertEquals("hunter2", value)
        // No `| base64 -d`: a pipeline means a shell, and a shell means the
        // decoded value can surface in a process listing.
        assertEquals(
            "kubectl --namespace=cityscape get secret cityscape-evaluator-oidc " +
                "'--output=jsonpath={.data.client-secret}'",
            recorder.commands.single(),
        )
    }

    @Test
    fun `a secret provider is secret by default`() {
        assertTrue(fromKubernetesSecret("s", "k").secret)
    }

    @Test
    fun `the description says where the value comes from and not what it is`() {
        val description = fromKubernetesSecret("my-secret", "password", namespace = "ns").description
        assertEquals("<k8s-secret ns/my-secret#password>", description)
    }

    @Test
    fun `a missing key is reported as a missing key`() = runBlocking {
        val error = runCatching {
            withContext(scope(Recorder(stdout = ""))) {
                fromKubernetesSecret("my-secret", "absent", namespace = "ns").get()
            }
        }.exceptionOrNull()

        assertTrue(
            error?.message?.contains("has no key 'absent'") == true,
            "expected a missing-key message, got: ${error?.message}",
        )
    }

    @Test
    fun `a kube context is passed through when given`() = runBlocking {
        val recorder = Recorder(stdout = Base64.getEncoder().encodeToString("v".toByteArray()))
        withContext(scope(recorder)) {
            fromKubernetesSecret("s", "k", namespace = "ns", kubeContext = "prod").get()
        }
        assertTrue(recorder.commands.single().startsWith("kubectl --context=prod --namespace=ns"))
    }

    @Test
    fun `a config map is not secret and does contribute to task state`() {
        val provider = fromKubernetesConfigMap("settings", "region", namespace = "ns")
        assertEquals(false, provider.secret)
        assertTrue(provider.contributesToState)
    }
}
