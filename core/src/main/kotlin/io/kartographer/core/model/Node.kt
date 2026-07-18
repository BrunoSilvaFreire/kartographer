package io.kartographer.core.model

import io.kartographer.core.Property
import io.kartographer.core.Provider
import io.kartographer.core.TaskOutputProvider

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

    protected fun <T> output(resolver: () -> T): Provider<T> {
        return TaskOutputProvider(this, resolver)
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
