package io.kartographer.core.model

import java.nio.file.Path

interface UserPrompter {
    fun prompt(message: String, secret: Boolean = false): String
}

interface ExecutionEnvironment {
    suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean
    ): Int
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
    val task: Task? = null
)

sealed interface ExecutionResult {
    data object Success : ExecutionResult
    data object UpToDate : ExecutionResult
    
    data class Failure(val error: Throwable, val exitCode: Int? = null) : ExecutionResult
}

abstract class ExecSpec {
    var workingDir: String? = null
    var requiresElevation: Boolean = false
    val environment = mutableMapOf<String, String>()

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

suspend fun ExecutionContext.executeSpec(spec: ExecSpec): ExecutionResult {
    val mergedEnv = this.environment + spec.environment
    val resolvedWorkingDir = spec.workingDir ?: this.projectDir.toString()

    val cmdLine = spec.buildCommandLine()

    if (this.dryRun) {
        org.slf4j.LoggerFactory.getLogger("ExecSpec").info("[DRY-RUN] Executing: {}", cmdLine)
        return ExecutionResult.Success
    }

    val exitCode = this.executionEnvironment?.execute(cmdLine, resolvedWorkingDir, mergedEnv, spec.requiresElevation) ?: -1
    if (exitCode != 0) {
        return ExecutionResult.Failure(
            RuntimeException("Command '$cmdLine' failed with exit code $exitCode"),
            exitCode
        )
    }

    return ExecutionResult.Success
}

suspend fun ExecutionContext.executeBash(block: BashSpec.() -> Unit): ExecutionResult {
    val spec = BashSpec().apply(block)
    return executeSpec(spec)
}
