package io.kartographer.core

interface Provider<out T> {
    fun get(): T
    val producingTask: Task?
}

class Property<T>(private var provider: Provider<T>? = null) : Provider<T> {
    private var directValue: T? = null

    override fun get(): T {
        return provider?.get() ?: directValue ?: throw IllegalStateException("Property has not been initialized.")
    }

    override val producingTask: Task?
        get() = provider?.producingTask

    fun set(value: T) {
        directValue = value
        provider = null
    }

    fun set(sourceProvider: Provider<T>) {
        provider = sourceProvider
        directValue = null
    }

    fun isPresent(): Boolean {
        return provider != null || directValue != null
    }
}

class TaskOutputProvider<T>(
    override val producingTask: Task,
    private val resolver: () -> T
) : Provider<T> {
    override fun get(): T = resolver()
}
