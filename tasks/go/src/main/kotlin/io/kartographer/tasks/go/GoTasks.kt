package io.kartographer.tasks.go

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeSpec
import io.kartographer.core.model.executeBash
import java.time.Instant

class GoSpec : ExecSpec() {
    var goBin: String = "go"
    var command: String? = null
    val args = mutableListOf<String>()

    fun arg(vararg arguments: String) {
        args.addAll(arguments)
    }

    override fun buildCommandLine(): String {
        val cmd = buildList {
            add(goBin)
            if (command != null) {
                add(command!!)
            }
            addAll(args)
        }
        return cmd.joinToString(" ")
    }
}

suspend fun ExecutionContext.executeGo(block: GoSpec.() -> Unit): ExecutionResult<NoOutputs> {
    val spec = GoSpec().apply(block)
    return executeSpec(spec)
}

abstract class GoTask<I : InputSet, O : OutputSet>(name: String) : Task<I, O>(name) {
    init {
        topology {
            requiresCapability("go")
        }
    }
}

data class GoBuildInputs(
    var goBin: String,
    var output: String?,
    var mainFile: String?,
    var tags: List<String>,
    var ldflags: List<String>,
    var args: List<String>,
    var environment: Map<String, String>
) : InputSet()
class GoBuildOutputs : OutputSet() { val binary = output<String>("binary") }

open class GoBuild(name: String) : GoTask<GoBuildInputs, GoBuildOutputs>(name) {
    var goBin: String = "go"
    var output: String? = null
    var mainFile: String? = null
    val tags = mutableListOf<String>()
    val ldflags = mutableListOf<String>()
    val args = mutableListOf<String>()
    val environment = mutableMapOf<String, String>()
    var goos: String? = null
    var goarch: String? = null
    var cgoEnabled: Boolean? = null
    var embedMainBuildMetadata = false

    override suspend fun buildInputs(context: ExecutionContext): GoBuildInputs {
        val resolvedFlags = ldflags.toMutableList()
        if (embedMainBuildMetadata) {
            val version = capture(context, "git", "describe", "--tags", "--always", "--dirty").ifBlank { "dev" }
            val commit = capture(context, "git", "rev-parse", "--short", "HEAD").ifBlank { "unknown" }
            resolvedFlags += "-w -s -X main.Version=$version -X main.GitCommit=$commit -X main.BuildDate=${Instant.now()}"
        }
        val resolvedEnvironment = environment.toMutableMap()
        goos?.let { resolvedEnvironment["GOOS"] = it }
        goarch?.let { resolvedEnvironment["GOARCH"] = it }
        cgoEnabled?.let { resolvedEnvironment["CGO_ENABLED"] = if (it) "1" else "0" }
        return GoBuildInputs(goBin, output, mainFile, tags.toList(), resolvedFlags, args.toList(), resolvedEnvironment)
    }
    override fun buildOutputs() = GoBuildOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: GoBuildInputs): ExecutionResult<GoBuildOutputs> {
        val result = context.executeGo {
            goBin = inputSet.goBin
            command = "build"
            if (inputSet.tags.isNotEmpty()) arg("-tags", inputSet.tags.joinToString(","))
            if (inputSet.ldflags.isNotEmpty()) arg("-ldflags", inputSet.ldflags.joinToString(" "))
            arg(*inputSet.args.toTypedArray())
            if (inputSet.output != null) arg("-o", inputSet.output!!)
            arg(inputSet.mainFile ?: ".")
            for ((k, v) in inputSet.environment) {
                this.environment(k, v)
            }
        }
        return when (result) {
            is ExecutionResult.Failure -> failure(result.error, result.exitCode)
            else -> success { inputSet.output?.let(binary::set) }
        }
    }

    private fun capture(context: ExecutionContext, vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).directory(context.projectDir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else ""
    }.getOrDefault("")
}

data class GoRunInputs(
    var goBin: String,
    var target: String,
    var tags: List<String>,
    var args: List<String>,
    var environment: Map<String, String>
) : InputSet()

open class GoRun(name: String) : GoTask<GoRunInputs, NoOutputs>(name) {
    var goBin: String = "go"
    var target: String = "."
    val tags = mutableListOf<String>()
    val args = mutableListOf<String>()
    val environment = mutableMapOf<String, String>()

    init { alwaysRun() }

    override suspend fun buildInputs(context: ExecutionContext) =
        GoRunInputs(goBin, target, tags.toList(), args.toList(), environment.toMap())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GoRunInputs) = context.executeGo {
        goBin = inputSet.goBin
        command = "run"
        if (inputSet.tags.isNotEmpty()) arg("-tags", inputSet.tags.joinToString(","))
        arg(inputSet.target)
        arg(*inputSet.args.toTypedArray())
        environment.putAll(inputSet.environment)
    }
}

data class GoFormatInputs(var goBin: String, var targets: List<String>) : InputSet()

open class GoFormat(name: String) : GoTask<GoFormatInputs, NoOutputs>(name) {
    var goBin: String = "gofmt"
    val targets = mutableListOf(".")

    override suspend fun buildInputs(context: ExecutionContext) = GoFormatInputs(goBin, targets.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GoFormatInputs) = context.executeBash {
        command(inputSet.goBin)
        args("-w")
        args(inputSet.targets)
    }
}

open class GoPackageFormat(name: String) : GoTask<GoFormatInputs, NoOutputs>(name) {
    var goBin = "go"
    val targets = mutableListOf("./...")
    override suspend fun buildInputs(context: ExecutionContext) = GoFormatInputs(goBin, targets.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GoFormatInputs) = context.executeBash {
        command(inputSet.goBin); args("fmt"); args(inputSet.targets)
    }
}

data class GoCoverageInputs(
    var goBin: String,
    var packages: List<String>,
    var profile: String,
    var html: String,
) : InputSet()

class GoCoverage(name: String) : GoTask<GoCoverageInputs, NoOutputs>(name) {
    var goBin = "go"
    val packages = mutableListOf("./...")
    var profile = "coverage.out"
    var html = "coverage.html"
    override suspend fun buildInputs(context: ExecutionContext) = GoCoverageInputs(goBin, packages.toList(), profile, html)
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GoCoverageInputs): ExecutionResult<NoOutputs> {
        val test = context.executeBash { command(inputSet.goBin); args("test", "-v", "-coverprofile=${inputSet.profile}"); args(inputSet.packages) }
        if (test is ExecutionResult.Failure) return test
        return context.executeBash { command(inputSet.goBin); args("tool", "cover", "-html=${inputSet.profile}", "-o", inputSet.html) }
    }
}

data class GolangCiLintInputs(var executable: String, var targets: List<String>) : InputSet()

class GolangCiLint(name: String) : GoTask<GolangCiLintInputs, NoOutputs>(name) {
    var executable = "golangci-lint"
    val targets = mutableListOf("./...")
    override suspend fun buildInputs(context: ExecutionContext) = GolangCiLintInputs(executable, targets.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GolangCiLintInputs) = context.executeBash {
        command(inputSet.executable); args("run"); args(inputSet.targets)
    }
}

data class GoTestInputs(var packages: List<String>) : InputSet()

open class GoTest(name: String) : GoTask<GoTestInputs, NoOutputs>(name) {
    var packages = mutableListOf("./...")

    override suspend fun buildInputs(context: ExecutionContext) = GoTestInputs(packages.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GoTestInputs): ExecutionResult<NoOutputs> {
        return context.executeGo {
            command = "test"
            arg(*inputSet.packages.toTypedArray())
        }
    }
}

data class GoModInputs(var action: String) : InputSet()

open class GoMod(name: String) : GoTask<GoModInputs, NoOutputs>(name) {
    var action: String = "tidy"

    override suspend fun buildInputs(context: ExecutionContext) = GoModInputs(action)
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GoModInputs): ExecutionResult<NoOutputs> {
        return context.executeGo {
            command = "mod"
            arg(inputSet.action)
        }
    }
}
