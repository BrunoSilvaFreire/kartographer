package io.kartographer.tasks.npm

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash

data class NpmInputs(
    var npmBin: String,
    var workingDir: String?,
    var args: List<String>,
    var environment: Map<String, String>
) : InputSet()

abstract class NpmTask(name: String) : Task<NpmInputs, NoOutputs>(name) {
    var npmBin = "npm"
    var workingDir: String? = null
    val args = mutableListOf<String>()
    val environment = mutableMapOf<String, String>()

    init { topology { requiresCapability("node") } }

    protected abstract fun command(inputSet: NpmInputs): List<String>

    override suspend fun buildInputs(context: ExecutionContext) =
        NpmInputs(npmBin, workingDir, args.toList(), environment.toMap())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: NpmInputs) = context.executeBash {
        command(inputSet.npmBin)
        args(command(inputSet))
        inputSet.workingDir?.let { workingDir = it }
        environment.putAll(inputSet.environment)
    }
}

class NpmInstall(name: String) : NpmTask(name) {
    override fun command(inputSet: NpmInputs) = listOf("install") + inputSet.args
}

class NpmRunScript(name: String) : NpmTask(name) {
    var script = ""

    override suspend fun buildInputs(context: ExecutionContext) =
        super.buildInputs(context).also { it.args = listOf(script) + it.args }

    override fun command(inputSet: NpmInputs): List<String> {
        require(inputSet.args.firstOrNull()?.isNotBlank() == true) { "npm script is required" }
        return listOf("run") + inputSet.args
    }
}
