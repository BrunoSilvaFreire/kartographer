package io.kartographer.tasks

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Task
import io.kartographer.local.LocalProcessExecutor
import org.slf4j.LoggerFactory

open class DefaultTask(name: String) : Task(name) {
    private var action: (suspend (ExecutionContext) -> Any)? = null

    fun action(block: suspend (ExecutionContext) -> Any) {
        action = block
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val act = action ?: return ExecutionResult.Success
        return try {
            val res = act(context)
            res as? ExecutionResult ?: ExecutionResult.Success
        } catch (t: Throwable) {
            ExecutionResult.Failure(t)
        }
    }
}

