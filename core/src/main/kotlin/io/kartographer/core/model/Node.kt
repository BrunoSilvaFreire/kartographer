package io.kartographer.core.model

import io.kartographer.core.Property
import io.kartographer.core.Provider
import io.kartographer.core.TaskOutputProvider

const val SECRET_MASK = "***"

abstract class Node(val name: String) {
    val id: TaskId = TaskId(name)
    val dependencyObjects = mutableSetOf<Node>()
    val dependencyNames = mutableSetOf<String>()

    fun dependsOn(vararg nodes: Node) {
        dependencyObjects.addAll(nodes)
    }

    fun dependsOn(vararg nodeNames: String) {
        dependencyNames.addAll(nodeNames)
    }
}

// A Step in the DAG that doesn't change state.
abstract class Validation(name: String) : Node(name)

// Infers an action that is executed
abstract class Task(name: String) : Node(name) {
    val inputs = TaskInputs()
    val outputs = TaskOutputs()
    val topologyRequirements = mutableListOf<TopologyRequirement>()

    val registeredProperties = mutableListOf<Property<*>>()
    open val requiresElevation: Boolean get() = false

    protected fun <T> property(initBlock: (Property<T>.() -> Unit)? = null): Property<T> {
        val prop = Property<T>()
        registeredProperties.add(prop)
        initBlock?.invoke(prop)
        return prop
    }

    val declaredOutputs = mutableListOf<TaskOutputProvider<*>>()

    protected fun <T> output(name: String? = null, secret: Boolean = false, resolver: () -> T): Provider<T> {
        val provider = TaskOutputProvider(this, resolver, name, secret)
        if (name != null) declaredOutputs.add(provider)
        return provider
    }

    /**
     * Snapshot of everything this task declared as an output, for the pipeline summary.
     * Best-effort: an output whose value is unset, still unresolvable, or whose resolver
     * throws is simply omitted, so a task that failed halfway still reports what it got to.
     */
    fun collectOutputs(): Map<String, String> {
        val collected = linkedMapOf<String, String>()
        for (prop in registeredProperties) {
            val label = prop.outputName ?: continue
            if (!prop.isPresent()) continue
            val value = runCatching { prop.get() }.getOrNull() ?: continue
            collected[label] = if (prop.isSecret) SECRET_MASK else value.toString()
        }
        for (provider in declaredOutputs) {
            val label = provider.outputName ?: continue
            val value = runCatching { provider.get() }.getOrNull() ?: continue
            collected[label] = if (provider.isSecret) SECRET_MASK else value.toString()
        }
        return collected
    }

    val upToDateChecks = mutableListOf<io.kartographer.core.scheduler.UpToDateCheck>()

    val customFilesToTrack = mutableSetOf<String>()
    val customPropertiesToTrack = mutableMapOf<String, () -> Any?>()

    fun trackFile(path: String) {
        customFilesToTrack.add(path)
    }

    fun trackProperty(name: String, provider: () -> Any?) {
        customPropertiesToTrack[name] = provider
    }

    fun trackEnvVar(name: String) {
        trackProperty("env:$name") { System.getenv(name) }
    }

    fun trackSystemProperty(name: String) {
        trackProperty("sys:$name") { System.getProperty(name) }
    }

    fun upToDateWhen(check: io.kartographer.core.scheduler.UpToDateCheck) {
        upToDateChecks.add(check)
    }

    fun upToDateWhenEnvMatches(vararg names: String) {
        for (name in names) {
            trackEnvVar(name)
        }
        upToDateWhen { context, task ->
            val helpers = io.kartographer.core.scheduler.UpToDateHelpers
            helpers.envVarsMatch(*names).isUpToDate(context, task)
        }
    }

    fun upToDateWhenFilesMatch(vararg paths: String) {
        for (path in paths) {
            trackFile(path)
        }
        upToDateWhen { context, task ->
            val helpers = io.kartographer.core.scheduler.UpToDateHelpers
            helpers.customFilesMatch().isUpToDate(context, task)
        }
    }

    fun upToDateWhenPropertyMatches(name: String, provider: () -> Any?) {
        trackProperty(name, provider)
        upToDateWhen { context, task ->
            val helpers = io.kartographer.core.scheduler.UpToDateHelpers
            helpers.customPropertiesMatch().isUpToDate(context, task)
        }
    }

    fun upToDateWhenInputsNewerThanOutputs() {
        upToDateWhen { context, task ->
            val helpers = io.kartographer.core.scheduler.UpToDateHelpers
            helpers.inputsOlderThanOutputs().isUpToDate(context, task)
        }
    }

    suspend fun isUpToDate(context: ExecutionContext): Boolean {
        if (upToDateChecks.isNotEmpty()) {
            return upToDateChecks.all { it.isUpToDate(context, this) }
        }

        val hasDefaults = inputs.files.isNotEmpty() || inputs.dirs.isNotEmpty() ||
                          outputs.files.isNotEmpty() || outputs.dirs.isNotEmpty() ||
                          registeredProperties.isNotEmpty() ||
                          customFilesToTrack.isNotEmpty() || customPropertiesToTrack.isNotEmpty()

        if (hasDefaults) {
            val helpers = io.kartographer.core.scheduler.UpToDateHelpers
            return helpers.propertiesMatch().isUpToDate(context, this) &&
                   helpers.inputsMatch().isUpToDate(context, this) &&
                   helpers.outputsMatch().isUpToDate(context, this) &&
                   helpers.customPropertiesMatch().isUpToDate(context, this) &&
                   helpers.customFilesMatch().isUpToDate(context, this)
        }

        return false
    }

    fun topology(block: TopologyBuilder.() -> Unit) {
        val builder = TopologyBuilder()
        builder.block()
        topologyRequirements.addAll(builder.requirements)
    }

    abstract suspend fun execute(context: ExecutionContext): ExecutionResult
}
