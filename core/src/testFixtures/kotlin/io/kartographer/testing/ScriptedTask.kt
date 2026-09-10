package io.kartographer.testing

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.NoInputs
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task

/**
 * A task whose body is supplied as a lambda.
 *
 * This exists purely so scheduler / up-to-date / protocol tests can exercise the engine without
 * inventing a bespoke task type per assertion. It is deliberately confined to test fixtures:
 * real pipelines declare dedicated task types, which is what makes remote execution (where a task
 * is reconstructed from its class name) and CLI introspection work at all.
 */
open class ScriptedTask(name: String) : Task<NoInputs, NoOutputs>(name) {
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
