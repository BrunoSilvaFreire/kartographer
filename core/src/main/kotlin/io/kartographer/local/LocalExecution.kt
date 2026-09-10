package io.kartographer.local

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionObserver
import io.kartographer.core.model.Task
import io.kartographer.core.model.UserPrompter
import java.util.Scanner

class ConsoleUserPrompter(val task: Task<*, *>? = null, val observer: ExecutionObserver? = null) : UserPrompter {
    override fun forTask(task: io.kartographer.core.model.Node): UserPrompter =
        ConsoleUserPrompter(task as? Task<*, *>, observer)

    override fun prompt(message: String, secret: Boolean): String {
        observer?.onEvent(io.kartographer.core.model.ExecutionEvent.PromptRequested(task, message))
        val console = System.console()
        val result = if (console != null) {
            if (secret) {
                String(console.readPassword("") ?: CharArray(0))
            } else {
                console.readLine("") ?: ""
            }
        } else {
            val scanner = Scanner(System.`in`)
            if (scanner.hasNextLine()) scanner.nextLine() else ""
        }
        observer?.onEvent(io.kartographer.core.model.ExecutionEvent.PromptCompleted)
        return result
    }
}

class HostExecutionEnvironment(val context: ExecutionContext) : ExecutionEnvironment {
    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?
    ): Int {
        val executor = LocalProcessExecutor()
        executor.stdinChannel = context.stdinChannel

        val observer = context.observer
        val task = context.executionNode ?: context.task
        if (observer != null && task != null) {
            // Forward each line as it arrives. A ProcessBuilder redirect hands the file
            // descriptor straight to the child, so the JVM never sees the bytes and nothing
            // can be streamed — the pipe has to stay open. Callers that also want a log file
            // tee from the observer.
            executor.redirectErrorStream = false
            executor.stdoutConsumer = { line ->
                observer.onEvent(ExecutionEvent.TaskOutput(task, line, isError = false))
                onStdoutLine?.invoke(line)
            }
            executor.stderrConsumer = { line ->
                observer.onEvent(ExecutionEvent.TaskOutput(task, line, isError = true))
                onStderrLine?.invoke(line)
            }
        } else {
            context.redirectOutput?.let {
                executor.redirectOutput = it
            }
            context.redirectError?.let {
                executor.redirectError = it
                executor.redirectErrorStream = false
            }
            executor.stdoutConsumer = { line -> onStdoutLine?.invoke(line) }
            executor.stderrConsumer = { line -> onStderrLine?.invoke(line) }
            if (onStderrLine != null) {
                executor.redirectErrorStream = false
            }
        }

        var finalCommand = command
        // Already root (e.g. the whole karto run is wrapped in `llm-sudo`):
        // sudo needs no password, and prompting would block non-interactive runs.
        val alreadyRoot = System.getProperty("user.name") == "root"
        if (requiresElevation && alreadyRoot) {
            val escapedCommand = command.replace("\"", "\\\"")
            finalCommand = "sudo sh -c \"$escapedCommand\""
        } else if (requiresElevation && !System.getProperty("os.name").lowercase().contains("win")) {
            val password = context.variables.computeIfAbsent("SUDO_PASSWORD") {
                context.prompter.prompt("Password for sudo: ", secret = true)
            } as String
            executor.inputString = "$password\n"
            // Escape quotes inside command if necessary, though simpler to pass command
            val escapedCommand = command.replace("\"", "\\\"")
            finalCommand = "sudo -S -p \"\" sh -c \"$escapedCommand\""
        }
        
        return executor.execute(finalCommand, workingDir, environment)
    }

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean
    ): Int = execute(command, workingDir, environment, requiresElevation, null, null)
}
