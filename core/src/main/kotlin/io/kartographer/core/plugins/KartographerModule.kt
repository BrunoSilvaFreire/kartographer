package io.kartographer.core.plugins

import io.kartographer.core.model.Task

interface KartographerModule {
    val id: String
    val taskTypes: List<Class<out Task<*, *>>>
}
