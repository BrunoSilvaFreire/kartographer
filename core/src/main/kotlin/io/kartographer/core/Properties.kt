package io.kartographer.core

import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskInvocationRef

/**
 * A value resolved later, from wherever.
 *
 * Deliberately says nothing about tasks: a provider may be a task's output, a
 * Kubernetes secret, a Pulumi stack output or a file on disk, and only the first
 * of those has a producing task. Providers that do are [TaskBoundProvider].
 *
 * [get] suspends because resolving one can mean running a subprocess, and doing
 * that on the calling thread would block a scheduler worker.
 */
interface Provider<out T> {
    suspend fun get(): T

    /**
     * Stands in for the value wherever it is displayed rather than used — task
     * state, `karto tasks`, logs. Say where the value comes from, not what it
     * is: `<k8s-secret cityscape/my-secret#password>` is useful in a diff, the
     * value would be a leak.
     */
    val description: String get() = "<provider>"

    /** The resolved value must never reach a log, a state file or a remote worker. */
    val secret: Boolean get() = false

    /**
     * Resolve during up-to-date checking and hash the value into task state, so
     * that a changed Pulumi output invalidates the task that reads it.
     *
     * Off by default because it costs a subprocess on every check, and most
     * external values are credentials, where the check would be both expensive
     * and pointless.
     */
    val contributesToState: Boolean get() = false
}

/**
 * A provider whose value another task in this pipeline produces.
 *
 * The scheduler uses this to add the implicit dependency from a property to
 * whatever produces it. Only providers that genuinely come from a task
 * implement it — an external provider has no answer to give and is not asked.
 */
interface TaskBoundProvider<out T> : Provider<T> {
    val producingTask: Task<*, *>?
    val producingInvocation: TaskInvocationRef? get() = null
}

class Property<T>(private var provider: Provider<T>? = null) : Provider<T> {
    private var directValue: T? = null
    private var directValuePresent = false
    var cliName: String? = null
        private set
    var cliDescription: String? = null
        private set
    var completionProvider: (suspend () -> List<String>)? = null
        private set
    // Task properties derive their type from the declaring field via reflection;
    // group properties have no field, so their type is recorded here for display.
    var typeName: String? = null

    /** The provider backing this property, when it is not holding a direct value. */
    val backingProvider: Provider<T>?
        get() = provider

    override suspend fun get(): T {
        provider?.let { return it.get() }
        if (!directValuePresent) throw IllegalStateException("Property has not been initialized.")
        @Suppress("UNCHECKED_CAST")
        return directValue as T
    }

    override val description: String
        get() = provider?.description ?: "<value>"

    override val secret: Boolean
        get() = provider?.secret ?: false

    override val contributesToState: Boolean
        get() = provider?.contributesToState ?: false

    val producingTask: Task<*, *>?
        get() = (provider as? TaskBoundProvider)?.producingTask

    val producingInvocation: TaskInvocationRef?
        get() = (provider as? TaskBoundProvider)?.producingInvocation

    fun set(value: T) {
        directValue = value
        directValuePresent = true
        provider = null
    }

    fun setProvider(sourceProvider: Provider<T>) {
        provider = sourceProvider
        directValue = null
        directValuePresent = false
    }

    fun isPresent(): Boolean {
        return provider != null || directValuePresent
    }

    /**
     * How this property appears in a task state file.
     *
     * Never the resolved value of a provider unless that provider asked to
     * contribute — a credential read from the cluster must not end up in a
     * state file under `.karto` just because nothing inside the pipeline
     * produced it. Suspends because an opted-in provider is resolved here.
     */
    suspend fun renderForState(): String {
        val backing = provider
        return when {
            backing == null -> if (directValuePresent) get().toString() else ""
            backing.secret -> backing.description
            backing.contributesToState -> get().toString()
            else -> backing.description
        }
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

    /**
     * The value, or null when the property was never set.
     *
     * A function rather than a `val`, because Kotlin property getters cannot
     * suspend and resolving a provider now can.
     */
    suspend fun orNull(): T? = if (isPresent()) get() else null

    /**
     * The directly-set value, or null — never resolves a provider.
     *
     * For the handful of reads that happen while the graph is still being built
     * and cannot suspend: `requiresElevation`, a configured-dependency lambda,
     * a `target(...)` accumulator. A provider-backed property reads as absent
     * there, which is correct — its value does not exist yet.
     */
    @Suppress("UNCHECKED_CAST")
    fun directOrNull(): T? = if (directValuePresent) directValue as T else null

    data class Snapshot<T>(val provider: Provider<T>?, val directValue: T?, val directValuePresent: Boolean)

    fun snapshot(): Snapshot<T> = Snapshot(provider, directValue, directValuePresent)

    fun restore(snapshot: Snapshot<T>) {
        provider = snapshot.provider
        directValue = snapshot.directValue
        directValuePresent = snapshot.directValuePresent
    }
}
