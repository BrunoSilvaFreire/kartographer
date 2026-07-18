package io.kartographer.tasks.python

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
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

suspend fun ExecutionContext.executePython(block: PythonSpec.() -> Unit): ExecutionResult {
    val spec = PythonSpec().apply(block)
    return executeSpec(spec)
}

/**
 * General-purpose Python execution task.
 * Abstracts away the package manager (venv vs system vs poetry).
 */
open class PythonExecTask(name: String) : DefaultTask(name) {
    // Execution modes
    val venv = property<String>()
    
    // The Python command or module to execute
    val scriptPath = property<String>()
    val moduleName = property<String>()
    val pythonBin = property<String> { set("python3") }
    
    val environment = mutableMapOf<String, String>()
    
    val args = mutableListOf<String>()

    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("python") }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return context.executePython {
            this.venv = this@PythonExecTask.venv.orNull
            this.scriptPath = this@PythonExecTask.scriptPath.orNull
            this.moduleName = this@PythonExecTask.moduleName.orNull
            this.pythonBin = this@PythonExecTask.pythonBin.get()
            this.args.addAll(this@PythonExecTask.args)
            for ((k, v) in this@PythonExecTask.environment) {
                this.environment(k, v)
            }
        }
    }
}
