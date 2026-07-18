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
