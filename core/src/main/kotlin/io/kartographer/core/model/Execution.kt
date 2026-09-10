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
    val stdinChannel: kotlinx.coroutines.channels.ReceiveChannel<String>? = null
)

fun <T> ExecutionContext.emitOutput(output: Output<T>, value: T) {
    output.set(value)
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
        val escapedArgs = args.map { 
            val hasSpecial = it.any { c -> c in " \t\n|&;()<>*?[]$\"'\\!{}^~" }
            if (hasSpecial) "'${it.replace("'", "'\\''")}'" else it 
        }
        return if (escapedArgs.isEmpty()) exe else "$exe ${escapedArgs.joinToString(" ")}"
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
