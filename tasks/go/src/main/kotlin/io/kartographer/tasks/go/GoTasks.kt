package io.kartographer.tasks.go

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeSpec

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

data class GoBuildInputs(var output: String?, var mainFile: String?, var environment: Map<String, String>) : InputSet()
class GoBuildOutputs : OutputSet() { val binary = output<String>("binary") }

open class GoBuild(name: String) : GoTask<GoBuildInputs, GoBuildOutputs>(name) {
    var output: String? = null
    var mainFile: String? = null
    val environment = mutableMapOf<String, String>()

    override suspend fun buildInputs(context: ExecutionContext) = GoBuildInputs(output, mainFile, environment.toMap())
    override fun buildOutputs() = GoBuildOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: GoBuildInputs): ExecutionResult<GoBuildOutputs> {
        val result = context.executeGo {
            command = "build"
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
