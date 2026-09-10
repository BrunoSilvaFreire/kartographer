package io.kartographer.tasks.pulumi

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash

data class PulumiInputs(
    var pulumiBin: String,
    var stack: String?,
    var cwd: String?,
    var args: List<String>
) : InputSet()

open class PulumiTask(name: String) : Task<PulumiInputs, NoOutputs>(name) {
    val pulumiBin = property<String> { set("pulumi") }
    val stack = property<String>()
    val cwd = property<String>()
    
    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("pulumi") }
    }

    override suspend fun buildInputs(context: ExecutionContext) = PulumiInputs(
        pulumiBin.get(), stack.orNull(), cwd.orNull(), args.toList()
    )

    override fun buildOutputs() = NoOutputs()

    protected suspend fun runPulumi(context: ExecutionContext, inputSet: PulumiInputs, command: String): ExecutionResult<NoOutputs> {
        return context.executeBash {
            command(inputSet.pulumiBin)
            args(command)
            if (inputSet.stack != null) {
                args("--stack", inputSet.stack!!)
            }
            if (inputSet.cwd != null) {
                args("--cwd", inputSet.cwd!!)
            }
            args(inputSet.args)
        }
    }

    override suspend fun execute(context: ExecutionContext, inputSet: PulumiInputs): ExecutionResult<NoOutputs> {
        return runPulumi(context, inputSet, "preview") // Default behavior
    }
}

open class PulumiUp(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext, inputSet: PulumiInputs): ExecutionResult<NoOutputs> {
        return runPulumi(context, inputSet, "up")
    }
}

open class PulumiPreview(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext, inputSet: PulumiInputs): ExecutionResult<NoOutputs> {
        return runPulumi(context, inputSet, "preview")
    }
}

open class PulumiRefresh(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext, inputSet: PulumiInputs): ExecutionResult<NoOutputs> {
        return runPulumi(context, inputSet, "refresh")
    }
}

open class PulumiDestroy(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext, inputSet: PulumiInputs): ExecutionResult<NoOutputs> {
        return runPulumi(context, inputSet, "destroy")
    }
}

open class PulumiInstall(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext, inputSet: PulumiInputs): ExecutionResult<NoOutputs> {
        return runPulumi(context, inputSet, "install")
    }
}

data class PulumiPackageAddInputs(
    var pulumiBin: String,
    var cwd: String?,
    var kind: String,
    var source: String
) : InputSet()

open class PulumiPackageAdd(name: String) : Task<PulumiPackageAddInputs, NoOutputs>(name) {
    val pulumiBin = property<String> { set("pulumi") }
    val cwd = property<String>()
    val kind = property<String> { set("terraform-provider") }
    val source = property<String>()

    init { topology { requiresCapability("pulumi") } }

    override suspend fun buildInputs(context: ExecutionContext) =
        PulumiPackageAddInputs(pulumiBin.get(), cwd.orNull(), kind.get(), source.get())

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: PulumiPackageAddInputs) = context.executeBash {
        command(inputSet.pulumiBin)
        args("package", "add", inputSet.kind, inputSet.source)
        inputSet.cwd?.let { workingDir = it }
    }
}

data class PulumiStackOutputInputs(
    var pulumiBin: String,
    var stack: String,
    var cwd: String?,
    var outputFile: String?
) : InputSet()

/** `pulumi stack output --json`, optionally captured into a file relative to [cwd]. */
open class PulumiStackOutput(name: String) : Task<PulumiStackOutputInputs, NoOutputs>(name) {
    val pulumiBin = property<String> { set("pulumi") }
    val stack = property<String>()
    val cwd = property<String>()
    val outputFile = property<String>()

    init { alwaysRun(); topology { requiresCapability("pulumi") } }

    override suspend fun buildInputs(context: ExecutionContext) =
        PulumiStackOutputInputs(pulumiBin.get(), stack.get(), cwd.orNull(), outputFile.orNull())

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: PulumiStackOutputInputs): ExecutionResult<NoOutputs> {
        val captured = StringBuilder()
        val result = context.executeBash {
            command(inputSet.pulumiBin)
            args("stack", "output", "--json", "--stack", inputSet.stack)
            inputSet.cwd?.let { workingDir = it }
            if (inputSet.outputFile != null) onStdoutLine = { captured.append(it).append('\n') }
        }
        val target = inputSet.outputFile
        if (result is ExecutionResult.Success && target != null && !context.dryRun) {
            val base = context.projectDir.resolve(inputSet.cwd ?: ".")
            val file = base.resolve(target).toFile()
            file.parentFile?.mkdirs()
            file.writeText(captured.toString())
        }
        return result
    }
}
