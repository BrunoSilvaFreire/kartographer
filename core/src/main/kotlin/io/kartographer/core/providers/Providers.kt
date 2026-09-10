package io.kartographer.core.providers

import io.kartographer.core.Provider
import io.kartographer.core.model.BashSpec
import io.kartographer.core.model.currentProviderResolution
import java.io.File

/**
 * The value of [name] in the environment of the shell that started the run.
 *
 * Not `System.getenv`: inside the daemon that is whatever environment the daemon
 * happened to start with, which is a different and much more surprising thing.
 * The run's context carries the client's environment.
 */
fun fromEnvironment(name: String): Provider<String> = object : Provider<String> {
    override val description = "<env $name>"
    override suspend fun get(): String =
        currentProviderResolution().context.environment[name] ?: ""
}

/** The trimmed contents of [path], or the empty string when it does not exist. */
fun fromFile(path: String): Provider<String> = object : Provider<String> {
    override val description = "<file $path>"
    override suspend fun get(): String {
        val file = File(path).let {
            if (it.isAbsolute) it else currentProviderResolution().context.projectDir.resolve(path).toFile()
        }
        return if (file.isFile) file.readText().trim() else ""
    }
}

/**
 * The primitive every tool-specific helper builds on: run a command, keep its
 * stdout, hand it to [parse].
 *
 * Goes through the run's [io.kartographer.core.model.ExecutionEnvironment] rather
 * than a bare `ProcessBuilder`, so the command is observable and does not block
 * a scheduler thread. It does run under `--dry-run`: reading a value changes
 * nothing, and a dry run that invented one would not describe the real run.
 */
fun <T> fromCommand(
    executable: String,
    arguments: List<String>,
    describedAs: String,
    workingDir: String? = null,
    environment: Map<String, String> = emptyMap(),
    secret: Boolean = false,
    contributesToState: Boolean = false,
    parse: suspend (String) -> T,
): Provider<T> = CommandProvider(
    executable, arguments, describedAs, workingDir, environment, secret, contributesToState, parse
)

/** String-valued [fromCommand], trimming trailing newlines the way a shell would. */
fun fromCommand(
    executable: String,
    arguments: List<String>,
    describedAs: String,
    workingDir: String? = null,
    environment: Map<String, String> = emptyMap(),
    secret: Boolean = false,
    contributesToState: Boolean = false,
): Provider<String> = fromCommand(
    executable, arguments, describedAs, workingDir, environment, secret, contributesToState
) { it.trim() }

private class CommandProvider<T>(
    private val executable: String,
    private val arguments: List<String>,
    override val description: String,
    private val workingDir: String?,
    private val environment: Map<String, String>,
    override val secret: Boolean,
    override val contributesToState: Boolean,
    private val parse: suspend (String) -> T,
) : Provider<T> {

    override suspend fun get(): T {
        val resolution = currentProviderResolution()
        return resolution.resolve(this) {
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val spec = BashSpec().apply {
                command(executable)
                args(arguments)
                this.workingDir = this@CommandProvider.workingDir
                environment.putAll(this@CommandProvider.environment)
                onStdoutLine = { line -> stdout.append(line).append('\n') }
                onStderrLine = { line -> stderr.append(line).append('\n') }
            }
            val context = resolution.context
            // Deliberately not through `executeSpec`: that skips the command
            // under --dry-run and reports success, which here would hand the
            // parser empty output and invent a value. A provider only *reads*,
            // so it runs in a dry run too — that is what makes the dry run an
            // accurate picture of the real one.
            val exitCode = context.executionEnvironment?.execute(
                spec.buildCommandLine(),
                spec.workingDir ?: context.projectDir.toString(),
                context.environment + spec.environment,
                spec.requiresElevation,
                spec.onStdoutLine,
                spec.onStderrLine,
            ) ?: throw IllegalStateException(
                "Could not resolve $description: the run has no execution environment."
            )
            if (exitCode != 0) {
                throw IllegalStateException(
                    "Could not resolve $description: '$executable ${arguments.joinToString(" ")}' " +
                        "failed with exit code $exitCode." +
                        stderr.toString().trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
                )
            }
            parse(stdout.toString())
        }
    }
}

/**
 * [this], unless it resolves to nothing, in which case [fallback].
 *
 * "Nothing" is null, a blank string, or a throw — a missing file and an
 * unreadable one are the same answer to the caller, and the point of a chain is
 * to keep asking. The last link's failure is the one that surfaces.
 */
infix fun <T : Any> Provider<T>.orElse(fallback: Provider<T>): Provider<T> {
    val first = this
    return object : Provider<T> {
        override val description = "${first.description} orElse ${fallback.description}"
        override val secret = first.secret || fallback.secret
        override val contributesToState = first.contributesToState || fallback.contributesToState

        override suspend fun get(): T {
            val value = try {
                first.get()
            } catch (error: Throwable) {
                null
            }
            if (value != null && !(value is String && value.isBlank())) return value
            return fallback.get()
        }
    }
}

/** [this], with [transform] applied to the resolved value. */
fun <T, R> Provider<T>.map(transform: suspend (T) -> R): Provider<R> {
    val source = this
    return object : Provider<R> {
        override val description = source.description
        override val secret = source.secret
        override val contributesToState = source.contributesToState
        override suspend fun get(): R = transform(source.get())
    }
}

/** A constant, for tests and for defaults in a chain. */
fun <T> providerOf(value: T, isSecret: Boolean = false): Provider<T> = object : Provider<T> {
    override val description = if (isSecret) "<constant>" else "<constant $value>"
    override val secret = isSecret
    override suspend fun get(): T = value
}
