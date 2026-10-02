package io.kartographer.tasks.buf

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash

data class BufInputs(
    var bufBin: String,
    var workingDir: String?,
    var args: List<String>,
    var environment: Map<String, String> = emptyMap()
) : InputSet()

abstract class BufTask(name: String) : Task<BufInputs, NoOutputs>(name) {
    var bufBin: String = "buf"
    var workingDir: String? = null
    val args = mutableListOf<String>()
    val environment = mutableMapOf<String, String>()

    init {
        topology {
            requiresCapability("buf")
        }
        alwaysRun()
    }

    override fun buildOutputs(): NoOutputs = NoOutputs()

    protected abstract fun command(inputSet: BufInputs): List<String>

    override suspend fun buildInputs(context: ExecutionContext): BufInputs =
        BufInputs(bufBin, workingDir, args.toList(), environment.toMap())

    override suspend fun execute(context: ExecutionContext, inputSet: BufInputs): ExecutionResult<NoOutputs> =
        context.executeBash {
            command(inputSet.bufBin)
            args(command(inputSet))
            inputSet.workingDir?.let { workingDir = it }
            environment.putAll(inputSet.environment)
        }
}

class BufGenerate(name: String) : BufTask(name) {
    override fun command(inputSet: BufInputs): List<String> =
        listOf("generate") + inputSet.args
}

class BufLint(name: String) : BufTask(name) {
    override fun command(inputSet: BufInputs): List<String> =
        listOf("lint") + inputSet.args
}

class BufBreaking(name: String) : BufTask(name) {
    var against: String? = null

    override suspend fun buildInputs(context: ExecutionContext): BufInputs {
        val extra = if (!against.isNullOrBlank()) listOf("--against", against!!) else emptyList()
        return BufInputs(bufBin, workingDir, extra + args, environment.toMap())
    }

    override fun command(inputSet: BufInputs): List<String> =
        listOf("breaking") + inputSet.args
}
