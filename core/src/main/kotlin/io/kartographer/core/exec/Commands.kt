package io.kartographer.core.exec

import io.kartographer.core.model.BashSpec
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeSpec

class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val combined: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs one command through the session's execution environment, capturing stdout and stderr.
 *
 * Argument lists only — deliberately never `bash -c` — so the same code works under `cmd.exe`.
 */
suspend fun ExecutionContext.runCommand(
    executable: String,
    arguments: List<String>,
    workDir: String? = null,
    executeDuringDryRun: Boolean = false
): CommandResult {
    val out = StringBuilder()
    val err = StringBuilder()
    val spec = BashSpec().apply {
        command(executable)
        args(arguments)
        this.workingDir = workDir
        onStdoutLine = { out.appendLine(it) }
        onStderrLine = { err.appendLine(it) }
    }
    val result = if (dryRun && executeDuringDryRun) {
        val exitCode = executionEnvironment?.execute(
            spec.buildCommandLine(),
            spec.workingDir ?: projectDir.toString(),
            environment + spec.environment,
            spec.requiresElevation,
            spec.onStdoutLine,
            spec.onStderrLine
        ) ?: 1
        if (exitCode == 0) ExecutionResult.Success(io.kartographer.core.model.NoOutputs())
        else ExecutionResult.Failure(IllegalStateException("Command '$executable' failed with exit code $exitCode"), exitCode, io.kartographer.core.model.NoOutputs())
    } else executeSpec(spec)
    val code = when (result) {
        is ExecutionResult.Failure -> result.exitCode ?: 1
        else -> 0
    }
    return CommandResult(code, out.toString().trimEnd(), err.toString().trimEnd())
}

/** Surfaces a line to the run's observer so `--dry-run` and progress are visible to the CLI. */
fun ExecutionContext.report(line: String) {
    val node = executionNode ?: task ?: return
    observer?.onEvent(ExecutionEvent.TaskOutput(node, line, isError = false))
}
