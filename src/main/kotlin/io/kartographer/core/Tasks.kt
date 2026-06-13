package io.kartographer.core

import io.kartographer.local.LocalProcessExecutor

open class DefaultTask(name: String) : Task(name) {
    private var action: (suspend (ExecutionContext) -> Unit)? = null

    fun action(block: suspend (ExecutionContext) -> Unit) {
        action = block
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val act = action ?: return ExecutionResult.Success
        return try {
            act(context)
            ExecutionResult.Success
        } catch (t: Throwable) {
            ExecutionResult.Failure(t)
        }
    }
}

open class ExecTask(name: String) : Task(name) {
    val commands = mutableListOf<String>()
    var workingDir: String? = null
    val environment = mutableMapOf<String, String>()

    fun exec(command: String) {
        commands.add(command)
    }

    fun local(block: ExecTask.() -> Unit) {
        this.block()
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val executor = LocalProcessExecutor()
        val mergedEnv = context.environment + environment
        val resolvedWorkingDir = workingDir ?: context.projectDir.toString()

        for (cmd in commands) {
            val exitCode = executor.execute(cmd, resolvedWorkingDir, mergedEnv)
            if (exitCode != 0) {
                return ExecutionResult.Failure(
                    RuntimeException("Command '$cmd' failed with exit code $exitCode"),
                    exitCode
                )
            }
        }
        return ExecutionResult.Success
    }
}
