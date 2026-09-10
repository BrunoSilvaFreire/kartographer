package io.kartographer.tasks.dart

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.Provider
import io.kartographer.core.model.executeBash
import io.kartographer.core.model.executeSpec
import io.kartographer.core.providers.providerOf
import java.io.File

/**
 * Plain Dart — not Flutter.
 *
 * The two SDKs overlap enough to be confusing and not enough to share a task:
 * `dart` has no devices, no build modes and no `--dart-define` for a running
 * program, so a Dart entrypoint is configured the ordinary way, through its
 * environment and its arguments. Flutter tasks live in `tasks/flutter`.
 */
class DartSpec : ExecSpec() {
    var dartBin: String = "dart"
    var command: String? = null
    val args = mutableListOf<String>()

    fun arg(vararg arguments: String) {
        args.addAll(arguments)
    }

    fun arg(arguments: Iterable<String>) {
        args.addAll(arguments)
    }

    override fun buildCommandLine(): String {
        val parts = buildList {
            add(dartBin)
            command?.let { add(it) }
            addAll(args)
        }
        return parts.joinToString(" ") { quoteIfNeeded(it) }
    }
}

/**
 * Arguments reach a shell, so anything with metacharacters needs quoting — a
 * `--threshold` is safe, a `--query 'a b'` is not, and the difference is not
 * something a caller should have to think about.
 */
internal fun quoteIfNeeded(argument: String): String {
    val needsQuoting = argument.isEmpty() ||
        argument.any { it in " \t\n|&;()<>*?[]$\"'\\!{}^~" }
    return if (needsQuoting) "'${argument.replace("'", "'\\''")}'" else argument
}

suspend fun ExecutionContext.executeDart(block: DartSpec.() -> Unit): ExecutionResult<NoOutputs> =
    executeSpec(DartSpec().apply(block))

abstract class DartTask<I : InputSet, O : OutputSet>(name: String) : Task<I, O>(name) {
    init {
        topology {
            requiresCapability("dart")
        }
    }

    /**
     * Where the pubspec lives, when that is not the project root.
     *
     * A repo often holds several Dart packages, and `dart` resolves the package
     * from the working directory rather than from a flag.
     */
    val packageDir = property<String>()

    val dartBin = property<String> { set("dart") }

    private val extraEnvironment = mutableMapOf<String, Provider<String>>()

    /** An environment variable for the child process, on top of the inherited one. */
    fun environment(key: String, value: String) {
        extraEnvironment[key] = providerOf(value)
    }

    /**
     * An environment variable whose value is resolved when the task runs — a
     * Kubernetes secret, a Pulumi output, anything a [Provider] can reach.
     */
    fun environment(key: String, value: Provider<String>) {
        extraEnvironment[key] = value
    }

    fun environment(values: Map<String, String>) {
        values.forEach { (key, value) -> environment(key, value) }
    }

    /** Resolves every declared variable. Call from `buildInputs`. */
    protected suspend fun resolveEnvironment(): Map<String, String> =
        extraEnvironment.mapValues { (_, provider) -> provider.get() }
}

data class DartRunInputs(
    var target: String,
    var packageDir: String?,
    var dartBin: String,
    var enableAsserts: Boolean,
    var environment: Map<String, String>,
    var args: List<String>,
) : InputSet()

/**
 * Runs a Dart entrypoint — `dart run <target> [args]`.
 *
 * Like `FlutterRun`, this launches a program and produces no cacheable output,
 * so it always runs.
 */
open class DartRun(name: String) : DartTask<DartRunInputs, NoOutputs>(name) {
    val target = property<String> { set("bin/main.dart") }
        .withCLI("target", "Dart entrypoint to run")
    val enableAsserts = property<Boolean> { set(false) }
        .withCLI("enable-asserts", "Run with assertions enabled") { completeWith("true", "false") }

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        alwaysRun()
    }

    override suspend fun buildInputs(context: ExecutionContext) = DartRunInputs(
        target.get(), packageDir.orNull(), dartBin.get(), enableAsserts.get(),
        resolveEnvironment(), args.toList()
    )

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: DartRunInputs,
    ): ExecutionResult<NoOutputs> = context.executeDart {
        dartBin = inputSet.dartBin
        command = "run"
        if (inputSet.enableAsserts) arg("--enable-asserts")
        arg(inputSet.target)
        // Everything after the entrypoint belongs to the program, not to `dart`.
        arg(inputSet.args)
        inputSet.packageDir?.let { workingDir = context.projectDir.resolve(it).toString() }
        environment.putAll(inputSet.environment)
    }
}

data class DartTestInputs(
    var targets: List<String>,
    var packageDir: String?,
    var dartBin: String,
    var namePattern: String?,
    var plainName: String?,
    var concurrency: Int?,
    var reporter: String?,
    var environment: Map<String, String>,
    var args: List<String>,
    var coverage: Boolean = false,
    var coveragePath: String? = null,
    var reportDir: String? = null,
    var generateHtml: Boolean = false,
) : InputSet()

open class DartTest(name: String) : DartTask<DartTestInputs, NoOutputs>(name) {
    val target = property<String>()
        .withCLI("target", "Specific test file or directory to run")
    val targets = property<List<String>> { set(emptyList()) }
    fun target(vararg paths: String) {
        targets.set((targets.directOrNull() ?: emptyList()) + paths)
    }

    val namePattern = property<String>()
        .withCLI("name", "Regexp matching test names")
    val plainName = property<String>()
        .withCLI("plain-name", "Substring matching test names")
    val concurrency = property<Int>()
    val reporter = property<String>()
        .withCLI("reporter", "Reporter to use") {
            completeWith("compact", "expanded", "failures-only", "github", "json", "silent")
        }

    val coverage = property<Boolean> { set(false) }
        .withCLI("coverage", "Collect coverage information") { completeWith("true", "false") }
    val coveragePath = property<String>()
        .withCLI("coverage-path", "Where to write the LCOV coverage file")
    val reportDir = property<String> { set("build/test-results") }
    val generateHtml = property<Boolean> { set(false) }
        .withCLI("html", "Generate HTML coverage report") { completeWith("true", "false") }

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    override suspend fun buildInputs(context: ExecutionContext): DartTestInputs {
        val baseTargets = target.orNull()?.let { listOf(it) } ?: (targets.orNull() ?: emptyList())
        val isCoverage = coverage.get()
        val extraArgs = if (isCoverage && args.none { it.startsWith("--coverage") }) {
            args.toList() + "--coverage=coverage"
        } else {
            args.toList()
        }
        return DartTestInputs(
            targets = baseTargets,
            packageDir = packageDir.orNull(),
            dartBin = dartBin.get(),
            namePattern = namePattern.orNull(),
            plainName = plainName.orNull(),
            concurrency = concurrency.orNull(),
            reporter = reporter.orNull(),
            environment = resolveEnvironment(),
            args = extraArgs,
            coverage = isCoverage,
            coveragePath = coveragePath.orNull(),
            reportDir = reportDir.orNull(),
            generateHtml = generateHtml.get(),
        )
    }

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: DartTestInputs,
    ): ExecutionResult<NoOutputs> {
        val pkgDir = inputSet.packageDir?.let { context.projectDir.resolve(it).toFile() }
            ?: context.projectDir.toFile()

        if (inputSet.targets.isNotEmpty()) {
            val hasTests = inputSet.targets.any { targetPath ->
                if (targetPath.endsWith(".dart")) {
                    true
                } else {
                    val dir = File(pkgDir, targetPath)
                    dir.exists() && (dir.isFile || dir.walkTopDown().any { it.isFile && it.name.endsWith("_test.dart") })
                }
            }

            if (!hasTests) {
                val msg = "No tests found in ${inputSet.targets.joinToString(", ")} for package ${pkgDir.name}"
                context.observer?.onEvent(ExecutionEvent.TaskOutput(this, msg, isError = false))
                println(msg)
                return ExecutionResult.Success(NoOutputs())
            }
        }

        val result = context.executeDart {
            dartBin = inputSet.dartBin
            command = "test"
            inputSet.namePattern?.let { arg("--name", it) }
            inputSet.plainName?.let { arg("--plain-name", it) }
            inputSet.concurrency?.let { arg("--concurrency", it.toString()) }
            inputSet.reporter?.let { arg("--reporter", it) }
            arg(inputSet.args)
            arg(inputSet.targets)
            inputSet.packageDir?.let { workingDir = context.projectDir.resolve(it).toString() }
            environment.putAll(inputSet.environment)
        }

        if (result is ExecutionResult.Failure) {
            return result
        }

        if (inputSet.coverage) {
            val covDir = File(pkgDir, "coverage")
            if (covDir.exists()) {
                val outLcov = inputSet.coveragePath?.let {
                    val f = File(it)
                    if (f.isAbsolute) f else File(context.projectDir.toFile(), it)
                } ?: File(pkgDir, "coverage/lcov.info")

                outLcov.parentFile?.mkdirs()

                context.executeDart {
                    dartBin = inputSet.dartBin
                    command = "run"
                    arg("coverage:format_coverage")
                    arg("--lcov")
                    arg("--in=coverage")
                    arg("--out=${outLcov.absolutePath}")
                    arg("--report-on=lib")
                    workingDir = pkgDir.absolutePath
                    environment.putAll(inputSet.environment)
                }

                if (inputSet.generateHtml && outLcov.exists()) {
                    val htmlDir = File(outLcov.parentFile, "html")
                    context.executeBash {
                        command("genhtml")
                        args(outLcov.absolutePath, "-o", htmlDir.absolutePath)
                    }
                }
            }
        }

        return result
    }
}

data class DartCompileInputs(
    var format: String,
    var target: String,
    var output: String?,
    var packageDir: String?,
    var dartBin: String,
    var environment: Map<String, String>,
    var args: List<String>,
) : InputSet()

class DartCompileOutputs : OutputSet() {
    val artifact = output<String>("artifact")
}

/** `dart compile <format> <target> -o <output>` — an AOT binary or a snapshot. */
open class DartCompile(name: String) : DartTask<DartCompileInputs, DartCompileOutputs>(name) {
    val format = property<String> { set("exe") }
        .withCLI("format", "Compilation format") {
            completeWith("exe", "aot-snapshot", "jit-snapshot", "kernel", "js", "wasm")
        }
    val target = property<String> { set("bin/main.dart") }
    val output = property<String>()
        .withCLI("output", "Where to write the compiled artifact")

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    override suspend fun buildInputs(context: ExecutionContext) = DartCompileInputs(
        format.get(), target.get(), output.orNull(), packageDir.orNull(), dartBin.get(),
        resolveEnvironment(), args.toList()
    )

    override fun buildOutputs() = DartCompileOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: DartCompileInputs,
    ): ExecutionResult<DartCompileOutputs> {
        val result = context.executeDart {
            dartBin = inputSet.dartBin
            command = "compile"
            arg(inputSet.format, inputSet.target)
            inputSet.output?.let { arg("-o", it) }
            arg(inputSet.args)
            inputSet.packageDir?.let { workingDir = context.projectDir.resolve(it).toString() }
            environment.putAll(inputSet.environment)
        }
        return when (result) {
            is ExecutionResult.Failure -> failure(result.error, result.exitCode)
            else -> success { inputSet.output?.let(artifact::set) }
        }
    }
}
