package io.kartographer.tasks.python

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
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
    var args: List<String>
) : InputSet()

abstract class PythonExecTaskBase<I : PythonExecInputs>(name: String) : Task<I, NoOutputs>(name) {
    // Execution modes
    val venv = property<String>()
    
    // The Python command or module to execute
    val scriptPath = property<String>()
    val moduleName = property<String>()
    val pythonBin = property<String> { set("python3") }
    
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
        args.toList()
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
