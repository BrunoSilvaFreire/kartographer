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
