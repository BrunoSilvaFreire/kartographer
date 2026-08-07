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
    var outputName: String? = null
        private set
    var isSecret: Boolean = false
        private set

    /**
     * Marks this property as a task output, reported in the pipeline summary under [name].
     * Secret outputs are listed by name with their value masked.
     */
    fun asOutput(name: String, secret: Boolean = false): Property<T> {
        outputName = name
        isSecret = secret
        return this
    }

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
    private val resolver: () -> T,
    val outputName: String? = null,
    val isSecret: Boolean = false
) : Provider<T> {
    override fun get(): T = resolver()
}
