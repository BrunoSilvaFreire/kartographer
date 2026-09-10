package io.kartographer

import io.kartographer.core.Property
import io.kartographer.core.Provider
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ProviderResolution
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.core.providers.fromCommand
import io.kartographer.core.providers.fromEnvironment
import io.kartographer.core.providers.fromFile
import io.kartographer.core.providers.map
import io.kartographer.core.providers.orElse
import io.kartographer.core.providers.providerOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

private class Recorder(private val stdout: String = "") : ExecutionEnvironment {
    val commands = mutableListOf<String>()
    var exitCode = 0

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?,
    ): Int {
        commands.add(command)
        if (stdout.isNotEmpty()) stdout.lines().forEach { onStdoutLine?.invoke(it) }
        return exitCode
    }

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
    ): Int = execute(command, workingDir, environment, requiresElevation, null, null)
}

private fun scope(
    recorder: Recorder = Recorder(),
    environment: Map<String, String> = emptyMap(),
): ProviderResolution = ProviderResolution(
    ExecutionContext(
        projectDir = Paths.get("").toAbsolutePath(),
        environment = environment,
        worker = Worker(WorkerId("test-worker")),
        executionEnvironment = recorder,
    ),
    producedOutputs = emptyMap(),
)

class ProvidersTest {

    @Test
    fun `a provider read outside a run says so instead of failing obscurely`() = runBlocking {
        val error = runCatching { fromEnvironment("ANYTHING").get() }.exceptionOrNull()
        assertTrue(
            error?.message?.contains("No provider resolution scope") == true,
            "expected an explanation, got: ${error?.message}",
        )
    }

    @Test
    fun `fromEnvironment reads the run's environment, not the daemon's`() = runBlocking {
        // The daemon's own environment is whatever launched it; the run carries
        // the invoking shell's.
        withContext(scope(environment = mapOf("TOKEN" to "from-the-run"))) {
            assertEquals("from-the-run", fromEnvironment("TOKEN").get())
        }
    }

    @Test
    fun `orElse falls through blank, missing and throwing links`() = runBlocking {
        val throwing = object : Provider<String> {
            override suspend fun get(): String = throw IllegalStateException("no kubeconfig")
        }
        withContext(scope(environment = mapOf("BLANK" to "  "))) {
            val chain = fromEnvironment("BLANK") orElse
                fromEnvironment("UNSET") orElse
                throwing orElse
                providerOf("last-resort")
            assertEquals("last-resort", chain.get())
        }
    }

    @Test
    fun `orElse stops at the first link that answers`() = runBlocking {
        val laterCalls = AtomicInteger()
        val later = object : Provider<String> {
            override suspend fun get(): String {
                laterCalls.incrementAndGet()
                return "later"
            }
        }
        withContext(scope(environment = mapOf("TOKEN" to "first"))) {
            assertEquals("first", (fromEnvironment("TOKEN") orElse later).get())
        }
        assertEquals(0, laterCalls.get())
    }

    @Test
    fun `a chain is secret when any link is`() {
        val chain = providerOf("plain") orElse providerOf("hidden", isSecret = true)
        assertTrue(chain.secret)
    }

    @Test
    fun `fromCommand runs through the execution environment and parses stdout`() = runBlocking {
        val recorder = Recorder(stdout = "  value-with-space  ")
        withContext(scope(recorder)) {
            val provider = fromCommand("kubectl", listOf("get", "thing"), "<thing>")
            assertEquals("value-with-space", provider.get())
        }
        assertEquals("kubectl get thing", recorder.commands.single())
    }

    @Test
    fun `a failing command names the provider rather than the exit code alone`() = runBlocking {
        val recorder = Recorder().apply { exitCode = 1 }
        withContext(scope(recorder)) {
            val error = runCatching {
                fromCommand("kubectl", listOf("get", "nope"), "<k8s-secret ns/nope#key>").get()
            }.exceptionOrNull()
            assertTrue(
                error?.message?.contains("<k8s-secret ns/nope#key>") == true,
                "expected the description in: ${error?.message}",
            )
        }
    }

    @Test
    fun `a provider resolves once per run however many tasks read it`() = runBlocking {
        val recorder = Recorder(stdout = "once")
        val provider = fromCommand("kubectl", listOf("get", "secret"), "<secret>")
        withContext(scope(recorder)) {
            assertEquals("once", provider.get())
            assertEquals("once", provider.get())
            assertEquals("once", provider.get())
        }
        assertEquals(1, recorder.commands.size, "expected one subprocess, got ${recorder.commands}")
    }

    @Test
    fun `a fresh run resolves again rather than serving a stale value`() = runBlocking {
        // The daemon keeps project instances between runs, so a provider that
        // cached in itself would hand yesterday's secret to today's run.
        val provider = fromCommand("kubectl", listOf("get", "secret"), "<secret>")
        val first = Recorder(stdout = "old")
        val second = Recorder(stdout = "new")
        withContext(scope(first)) { assertEquals("old", provider.get()) }
        withContext(scope(second)) { assertEquals("new", provider.get()) }
    }

    @Test
    fun `a failed resolution is not cached, so a retry within the run can succeed`() = runBlocking {
        val recorder = Recorder(stdout = "fine").apply { exitCode = 1 }
        val provider = fromCommand("kubectl", listOf("get", "secret"), "<secret>")
        withContext(scope(recorder)) {
            assertTrue(runCatching { provider.get() }.isFailure)
            recorder.exitCode = 0
            assertEquals("fine", provider.get())
        }
    }

    @Test
    fun `map transforms without losing the secret flag`() = runBlocking {
        val mapped = providerOf("abc", isSecret = true).map { it.uppercase() }
        assertTrue(mapped.secret)
        withContext(scope()) { assertEquals("ABC", mapped.get()) }
    }

    @Test
    fun `fromFile reads a file and reports a missing one as absent`() = runBlocking {
        val file = Files.createTempFile("provider", ".txt")
        Files.writeString(file, "contents\n")
        withContext(scope()) {
            assertEquals("contents", fromFile(file.toString()).get())
            assertEquals("", fromFile("${file}-does-not-exist").get())
        }
    }

    @Test
    fun `a secret never reaches the task state rendering`() = runBlocking {
        val property = Property<String>()
        property.setProvider(providerOf("hunter2", isSecret = true))
        val rendered = property.renderForState()
        assertFalse(rendered.contains("hunter2"), "state rendered the secret: $rendered")
        assertEquals("<constant>", rendered)
    }

    @Test
    fun `an opted-in provider contributes its value to task state`() = runBlocking {
        val contributing = object : Provider<String> {
            override val description = "<stack-output>"
            override val contributesToState = true
            override suspend fun get(): String = "v2"
        }
        val property = Property<String>()
        property.setProvider(contributing)
        assertEquals("v2", property.renderForState())
    }

    @Test
    fun `an ordinary provider renders as its description, not its value`() = runBlocking {
        val property = Property<String>()
        property.setProvider(providerOf("visible-but-uninteresting"))
        assertEquals("<constant visible-but-uninteresting>", property.renderForState())
    }

    @Test
    fun `a directly set property still renders its value`() = runBlocking {
        val property = Property<String>()
        property.set("plain")
        assertEquals("plain", property.renderForState())
    }

    @Test
    fun `directOrNull never resolves a provider`() {
        val property = Property<String>()
        property.setProvider(providerOf("resolved"))
        // Reads that happen while the graph is built cannot suspend, and a
        // provider-backed property genuinely has no value yet.
        assertEquals(null, property.directOrNull())
        assertTrue(property.isPresent())
    }
}
