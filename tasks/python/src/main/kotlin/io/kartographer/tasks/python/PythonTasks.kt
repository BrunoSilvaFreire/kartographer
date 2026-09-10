package io.kartographer.tasks.python

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash
import io.kartographer.core.model.executeSpec

class PythonSpec : ExecSpec() {
    var venv: String? = null
    var scriptPath: String? = null
    var moduleName: String? = null
    var pythonBin: String = "python3"
    
    val args = mutableListOf<String>()

    fun arg(vararg arguments: String) { args.addAll(arguments) }

    override fun buildCommandLine(): String {
        val execBin = if (venv != null) "$venv/bin/python" else pythonBin
        val cmd = buildList {
            add(execBin)
            if (moduleName != null) {
                add("-m")
                add(moduleName!!)
            } else if (scriptPath != null) {
                add(scriptPath!!)
            }
            addAll(args)
        }
        return cmd.joinToString(" ")
    }
}

suspend fun ExecutionContext.executePython(block: PythonSpec.() -> Unit): ExecutionResult<NoOutputs> {
    val spec = PythonSpec().apply(block)
    return executeSpec(spec)
}

/**
 * General-purpose Python execution task.
 * Abstracts away the package manager (venv vs system vs poetry).
 */
open class PythonExecInputs(
    var venv: String?,
    var scriptPath: String?,
    var moduleName: String?,
    var pythonBin: String,
    var environment: Map<String, String>,
    var args: List<String>,
    var workingDir: String? = null
) : InputSet()

abstract class PythonExecTaskBase<I : PythonExecInputs>(name: String) : Task<I, NoOutputs>(name) {
    // Execution modes
    val venv = property<String>()
    
    // The Python command or module to execute
    val scriptPath = property<String>()
    val moduleName = property<String>()
    val pythonBin = property<String> { set("python3") }
    val workingDir = property<String>()
    
    val environment = mutableMapOf<String, String>()
    
    val args = mutableListOf<String>()

    fun arg(vararg arguments: String) { args.addAll(arguments) }

    // Taps on the child's streams, in addition to the terminal. A subclass that wants a
    // log file must tee through these rather than redirecting the process, since a
    // redirected process shows nothing in the CLI for the whole run.
    var onStdoutLine: ((String) -> Unit)? = null
    var onStderrLine: ((String) -> Unit)? = null

    init {
        topology { requiresCapability("python") }
    }

    protected suspend fun pythonInputs() = PythonExecInputs(
        venv.orNull(),
        scriptPath.orNull(),
        moduleName.orNull(),
        pythonBin.get(),
        environment.toMap(),
        args.toList(),
        workingDir.orNull()
    )

    override fun buildOutputs() = NoOutputs()

    protected suspend fun executePythonInputs(
        context: ExecutionContext,
        inputSet: PythonExecInputs
    ): ExecutionResult<NoOutputs> {
        return context.executePython {
            this.venv = inputSet.venv
            this.scriptPath = inputSet.scriptPath
            this.moduleName = inputSet.moduleName
            this.pythonBin = inputSet.pythonBin
            this.args.addAll(inputSet.args)
            inputSet.workingDir?.let { this.workingDir = it }
            this.onStdoutLine = this@PythonExecTaskBase.onStdoutLine
            this.onStderrLine = this@PythonExecTaskBase.onStderrLine
            for ((k, v) in inputSet.environment) {
                this.environment(k, v)
            }
        }
    }
}

open class PythonExecTask(name: String) : PythonExecTaskBase<PythonExecInputs>(name) {
    override suspend fun buildInputs(context: ExecutionContext) = pythonInputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: PythonExecInputs
    ): ExecutionResult<NoOutputs> = executePythonInputs(context, inputSet)
}

data class PythonVenvInputs(
    var pythonBin: String,
    var venv: String,
    var requirements: String?,
    var upgradePip: Boolean
) : InputSet()

class PythonVenv(name: String) : Task<PythonVenvInputs, NoOutputs>(name) {
    var pythonBin = "python3"
    var venv = ".venv"
    var requirements: String? = "requirements.txt"
    var upgradePip = false

    override suspend fun buildInputs(context: ExecutionContext) =
        PythonVenvInputs(pythonBin, venv, requirements, upgradePip)
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: PythonVenvInputs): ExecutionResult<NoOutputs> {
        val create = context.executeBash { command(inputSet.pythonBin); args("-m", "venv", inputSet.venv) }
        if (create is ExecutionResult.Failure) return create
        val pip = "${inputSet.venv}/bin/pip"
        if (inputSet.upgradePip) {
            val upgrade = context.executeBash { command(pip); args("install", "--upgrade", "pip") }
            if (upgrade is ExecutionResult.Failure) return upgrade
        }
        return inputSet.requirements?.let { requirements ->
            context.executeBash { command(pip); args("install", "-r", requirements) }
        } ?: success()
    }
}

data class PoetryInputs(
    var poetryBin: String,
    var workingDir: String?,
    var args: List<String>
) : InputSet()

abstract class PoetryTask(name: String) : Task<PoetryInputs, NoOutputs>(name) {
    var poetryBin = "poetry"
    var workingDir: String? = null
    val args = mutableListOf<String>()
    protected abstract val commandName: String

    override suspend fun buildInputs(context: ExecutionContext) =
        PoetryInputs(poetryBin, workingDir, args.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: PoetryInputs) = context.executeBash {
        command(inputSet.poetryBin)
        args(commandName)
        args(inputSet.args)
        inputSet.workingDir?.let { workingDir = it }
    }
}

class PoetryCheck(name: String) : PoetryTask(name) { override val commandName = "check" }
class PoetryBuild(name: String) : PoetryTask(name) { override val commandName = "build" }
