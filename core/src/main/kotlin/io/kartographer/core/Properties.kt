package io.kartographer.core

import io.kartographer.core.model.Task

interface Provider<out T> {
    fun get(): T
    val producingTask: Task?
}

class Property<T>(private var provider: Provider<T>? = null) : Provider<T> {
    private var directValue: T? = null
    var cliName: String? = null
        private set
    var cliDescription: String? = null
        private set
    var completionProvider: (suspend () -> List<String>)? = null
        private set

    override fun get(): T {
        return provider?.get() ?: directValue ?: throw IllegalStateException("Property has not been initialized.")
    }

    override val producingTask: Task?
        get() = provider?.producingTask

    fun set(value: T) {
        directValue = value
        provider = null
    }

    fun setProvider(sourceProvider: Provider<T>) {
        provider = sourceProvider
        directValue = null
    }

    fun isPresent(): Boolean {
        return provider != null || directValue != null
    }

    var literalCompletions: List<String>? = null
        private set

    class CliBuilder {
        var completionProvider: (suspend () -> List<String>)? = null
        var literalCompletions: List<String>? = null
        
        fun completeWith(vararg values: String) {
            literalCompletions = values.toList()
            completionProvider = { literalCompletions!! }
        }
        
        fun completeWith(block: suspend () -> List<String>) {
            completionProvider = block
        }
    }

    fun withCLI(name: String, description: String = "", block: CliBuilder.() -> Unit = {}): Property<T> {
        this.cliName = name
        this.cliDescription = description
        val builder = CliBuilder()
        builder.block()
        this.completionProvider = builder.completionProvider
        this.literalCompletions = builder.literalCompletions
        return this
    }

    val orNull: T?
        get() = if (isPresent()) get() else null
}

class TaskOutputProvider<T>(
    override val producingTask: Task,
    private val resolver: () -> T
) : Provider<T> {
    override fun get(): T = resolver()
}
