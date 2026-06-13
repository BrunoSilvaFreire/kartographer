package io.kartographer.tasks.go

import io.kartographer.tasks.ExecTask
import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult

open class GoTask(name: String) : ExecTask(name) {
    init {
        topology {
            requiresCapability("go")
        }
    }
}

open class GoBuild(name: String) : GoTask(name) {
    var output: String? = null
    var mainFile: String? = null

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val outCmd = output?.let { "-o $it" } ?: ""
        val mainCmd = mainFile ?: "."
        commands.clear()
        exec("go build $outCmd $mainCmd")
        return super.execute(context)
    }
}

open class GoTest(name: String) : GoTask(name) {
    var packages = mutableListOf("./...")

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        commands.clear()
        exec("go test ${packages.joinToString(" ")}")
        return super.execute(context)
    }
}

open class GoMod(name: String) : GoTask(name) {
    var action: String = "tidy"

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        commands.clear()
        exec("go mod $action")
        return super.execute(context)
    }
}
