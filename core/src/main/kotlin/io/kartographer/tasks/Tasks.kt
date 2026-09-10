package io.kartographer.tasks

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Task
import io.kartographer.core.model.NoInputs
import io.kartographer.core.model.NoOutputs
import io.kartographer.local.LocalProcessExecutor
import org.slf4j.LoggerFactory

open class DefaultTask(name: String) : Task<NoInputs, NoOutputs>(name) {
    private var action: (suspend (ExecutionContext) -> Any)? = null

    fun action(block: suspend (ExecutionContext) -> Any) {
        action = block
    }

    override suspend fun buildInputs(context: ExecutionContext) = NoInputs()

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: NoInputs): ExecutionResult<NoOutputs> {
        val act = action ?: return success()
        return try {
            val res = act(context)
            @Suppress("UNCHECKED_CAST")
            (res as? ExecutionResult<NoOutputs>) ?: success()
        } catch (t: Throwable) {
            failure(t)
        }
    }
}
