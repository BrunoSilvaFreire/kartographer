package io.kartographer.cli.render

import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionObserver
import kotlinx.coroutines.channels.Channel

interface Renderer : ExecutionObserver {
    val events: Channel<ExecutionEvent>

    override fun onEvent(event: ExecutionEvent) {
        events.trySend(event)
    }

    suspend fun render()
}

/**
 * A prompt the daemon wants the user to answer, with enough context for a renderer to draw it
 * and later return the answer against the same [requestId].
 */
data class PromptRequest(
    val requestId: String,
    val task: String,
    val message: String,
    val isSecret: Boolean,
    val elevatedTasks: List<String>,
)

/** The user's answer to a [PromptRequest]. */
data class PromptAnswer(
    val requestId: String,
    val value: String,
)

/**
 * Implemented by renderers that own the terminal (the TUI) and therefore must collect prompt
 * input themselves instead of reading it from the console on the CLI thread.
 */
interface PromptRenderer {
    val promptAnswers: Channel<PromptAnswer>

    fun onPromptRequested(request: PromptRequest)
}
