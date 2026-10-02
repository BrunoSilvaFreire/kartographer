package io.kartographer.core.model

import java.nio.file.Path

interface UserPrompter {
    fun prompt(message: String, secret: Boolean = false): String
    fun forTask(task: Node): UserPrompter = this
}

interface ExecutionEnvironment {
    suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean
    ): Int = execute(command, workingDir, environment, requiresElevation, null, null)

    suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?
    ): Int = execute(command, workingDir, environment, requiresElevation)
}

data class ExecutionContext(
    val projectDir: Path,
    val environment: Map<String, String> = System.getenv(),
    val worker: Worker,
    val variables: java.util.concurrent.ConcurrentHashMap<String, Any> = java.util.concurrent.ConcurrentHashMap(),
    val observer: ExecutionObserver? = null,
    val dryRun: Boolean = false,
    val runId: String? = null,
    val redirectOutput: ProcessBuilder.Redirect? = null,
    val redirectError: ProcessBuilder.Redirect? = null,
    val prompter: UserPrompter = io.kartographer.local.ConsoleUserPrompter(),
    val executionEnvironment: ExecutionEnvironment? = null,
    val task: Task<*, *>? = null,
    val executionNode: Node? = null,
    val invocationId: String? = null,
    val inputHash: String? = null,
    val stdinChannel: kotlinx.coroutines.channels.ReceiveChannel<String>? = null,
    /** The permits this task holds; null outside a run, or for a task that declared none. */
    val semaphores: SemaphoreLeases? = null,
    /**
     * Every task this one depends on, transitively, by invocation id.
     *
     * For a task whose job is to say something about the run below it — collecting test
     * results, checking that everything expected actually reported. The ids are the keys of
     * [ProviderResolution.producedOutputs], so a task can pair what it expected with what
     * was produced, and tell "produced nothing" apart from "was never asked to run".
     */
    val dependencyTasks: Map<String, Task<*, *>> = emptyMap(),
    /** Scheduler-owned hooks used by service tasks. Pipeline code should use [markReady] and [emitOutput]. */
    val serviceReadySignal: (() -> Unit)? = null,
    val emittedOutputConsumer: ((String, Any?, Boolean) -> Unit)? = null
)

/** Marks a signal-based service ready. Repeated calls are intentionally harmless. */
fun ExecutionContext.markReady() {
    val signal = serviceReadySignal
        ?: throw IllegalStateException("markReady() is only available while a signal-based service is running.")
    signal()
}

/**
 * Hands back a semaphore permit before the task finishes.
 *
 * For the task that knows when its conflicting phase is over — a test run that owns a
 * shared asset directory only while it builds the bundle, say. Safe to call from a
 * process-output callback, and a no-op when the permit was never held.
 */
fun ExecutionContext.releaseSemaphore(handle: SemaphoreHandle) {
    semaphores?.release(handle)
}

fun ExecutionContext.releaseSemaphore(name: String) {
    semaphores?.release(name)
}

fun <T> ExecutionContext.emitOutput(output: Output<T>, value: T) {
    output.set(value)
    emittedOutputConsumer?.invoke(output.name, value, output.secret)
    val node = executionNode ?: task
    if (node != null) {
        observer?.onEvent(ExecutionEvent.TaskOutputProduced(node, output.name, value.toString(), output.secret))
    }
}

sealed interface ExecutionResult<out O : OutputSet> {
    val outputs: O

    data class Success<O : OutputSet>(override val outputs: O) : ExecutionResult<O>
    data class UpToDate<O : OutputSet>(override val outputs: O) : ExecutionResult<O>
    data class Failure<O : OutputSet>(
        val error: Throwable,
        val exitCode: Int? = null,
        override val outputs: O
    ) : ExecutionResult<O>
}

abstract class ExecSpec {
    var workingDir: String? = null
    var requiresElevation: Boolean = false
    val environment = mutableMapOf<String, String>()
    var onStdoutLine: ((String) -> Unit)? = null
    var onStderrLine: ((String) -> Unit)? = null

    abstract fun buildCommandLine(): String

    fun environment(key: String, value: String) {
        environment[key] = value
    }
}

class BashSpec : ExecSpec() {
    var command: String? = null
    val args = mutableListOf<String>()

    fun command(executable: String) {
        this.command = executable
    }

    fun args(vararg arguments: String) {
        this.args.addAll(arguments)
    }

    fun args(arguments: Iterable<String>) {
        this.args.addAll(arguments)
    }

    override fun buildCommandLine(): String {
        val exe = command ?: throw IllegalStateException("Command executable not specified in BashSpec")
        val quoted = if (isWindows()) args.map(::quoteForCmd) else args.map(::quoteForPosix)
        return if (quoted.isEmpty()) exe else "$exe ${quoted.joinToString(" ")}"
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun quoteForPosix(arg: String): String {
        val hasSpecial = arg.any { c -> c in " \t\n|&;()<>*?[]$\"'\\!{}^~" }
        return if (hasSpecial) "'${arg.replace("'", "'\\''")}'" else arg
    }

    /**
     * cmd.exe quoting. Wraps arguments that would otherwise be split or interpreted by the
     * shell. Embedded double quotes are backslash-escaped, which cmd passes through to the
     * child; this is enough for paths and URLs but is not a full cmd parser, so callers that
     * need shell operators must still build the whole command themselves.
     */
    private fun quoteForCmd(arg: String): String {
        val hasSpecial = arg.any { c -> c in " \t\n|&;()<>^\"%!" }
        return if (hasSpecial) "\"${arg.replace("\"", "\\\"")}\"" else arg
    }
}

suspend fun ExecutionContext.executeSpec(spec: ExecSpec): ExecutionResult<NoOutputs> {
    val mergedEnv = this.environment + spec.environment
    val resolvedWorkingDir = spec.workingDir ?: this.projectDir.toString()

    val cmdLine = spec.buildCommandLine()

    if (this.dryRun) {
        org.slf4j.LoggerFactory.getLogger("ExecSpec").info("[DRY-RUN] Executing: {}", cmdLine)
        return ExecutionResult.Success(NoOutputs())
    }

    val exitCode = this.executionEnvironment?.execute(
        cmdLine,
        resolvedWorkingDir,
        mergedEnv,
        spec.requiresElevation,
        spec.onStdoutLine,
        spec.onStderrLine
    ) ?: -1
    if (exitCode != 0) {
        return ExecutionResult.Failure(
            RuntimeException("Command '$cmdLine' failed with exit code $exitCode"),
            exitCode,
            NoOutputs()
        )
    }

    return ExecutionResult.Success(NoOutputs())
}

suspend fun ExecutionContext.executeBash(block: BashSpec.() -> Unit): ExecutionResult<NoOutputs> {
    val spec = BashSpec().apply(block)
    return executeSpec(spec)
}
