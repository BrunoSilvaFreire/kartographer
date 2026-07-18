package io.kartographer.tasks.pulumi

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeBash

open class PulumiTask(name: String) : DefaultTask(name) {
    val pulumiBin = property<String> { set("pulumi") }
    val stack = property<String>()
    val cwd = property<String>()
    
    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("pulumi") }
    }

    protected suspend fun runPulumi(context: ExecutionContext, command: String): ExecutionResult {
        return context.executeBash {
            command(pulumiBin.get())
            args(command)
            if (stack.isPresent()) {
                args("--stack", stack.get())
            }
            if (cwd.isPresent()) {
                args("--cwd", cwd.get())
            }
            args(this@PulumiTask.args)
        }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return runPulumi(context, "preview") // Default behavior
    }
}

open class PulumiUp(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return runPulumi(context, "up")
    }
}

open class PulumiPreview(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return runPulumi(context, "preview")
    }
}

open class PulumiRefresh(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return runPulumi(context, "refresh")
    }
}

open class PulumiDestroy(name: String) : PulumiTask(name) {
    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return runPulumi(context, "destroy")
    }
}
