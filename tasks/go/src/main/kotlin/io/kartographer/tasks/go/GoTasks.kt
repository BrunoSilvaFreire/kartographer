package io.kartographer.tasks.go

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.ExecSpec
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

suspend fun ExecutionContext.executeGo(block: GoSpec.() -> Unit): ExecutionResult {
    val spec = GoSpec().apply(block)
    return executeSpec(spec)
}

open class GoTask(name: String) : DefaultTask(name) {
    init {
        topology {
            requiresCapability("go")
        }
    }
}

open class GoBuild(name: String) : GoTask(name) {
    var output: String? = null
    var mainFile: String? = null
    val environment = mutableMapOf<String, String>()

    // Reported only when an explicit -o was configured; `go build` otherwise picks the name.
    val binary = output<String?>("binary") { output }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return context.executeGo {
            command = "build"
            if (output != null) arg("-o", output!!)
            arg(mainFile ?: ".")
            for ((k, v) in this@GoBuild.environment) {
                this.environment(k, v)
            }
        }
    }
}

open class GoTest(name: String) : GoTask(name) {
    var packages = mutableListOf("./...")

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return context.executeGo {
            command = "test"
            arg(*packages.toTypedArray())
        }
    }
}

open class GoMod(name: String) : GoTask(name) {
    var action: String = "tidy"

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return context.executeGo {
            command = "mod"
            arg(action)
        }
    }
}
