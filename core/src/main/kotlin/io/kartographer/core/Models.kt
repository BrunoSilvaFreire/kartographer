package io.kartographer.core

import java.nio.file.Path

@JvmInline
value class TaskId(val value: String) {
    override fun toString() = value
}

@JvmInline
value class WorkerId(val value: String) {
    override fun toString() = value
}

class TaskInputs {
    val files = mutableSetOf<String>()
    val dirs = mutableSetOf<String>()

    fun file(path: String) {
        files.add(path)
    }

    fun dir(path: String) {
        dirs.add(path)
    }
}

class TaskOutputs {
    val files = mutableSetOf<String>()
    val dirs = mutableSetOf<String>()

    fun file(path: String) {
        files.add(path)
    }

    fun dir(path: String) {
        dirs.add(path)
    }
}

sealed interface TopologyRequirement {
    data object LocalOnly : TopologyRequirement
    
    data class RequiresLabel(val key: String, val value: String) : TopologyRequirement
    
    data class PrefersLabel(val key: String, val value: String) : TopologyRequirement
    
    data class RequiresCapability(val capability: String) : TopologyRequirement
}

data class Worker(
    val id: WorkerId,
    val labels: Map<String, String> = emptyMap(),
    val capabilities: Set<String> = emptySet()
)

data class PlacementDecision(
    val taskId: TaskId,
    val workerId: WorkerId,
    val isSatisfied: Boolean,
    val reason: String? = null
)

data class ExecutionContext(
    val projectDir: Path,
    val environment: Map<String, String> = System.getenv(),
    val worker: Worker,
    val variables: java.util.concurrent.ConcurrentHashMap<String, Any> = java.util.concurrent.ConcurrentHashMap()
)

sealed interface ExecutionResult {
    data object Success : ExecutionResult
    data object UpToDate : ExecutionResult
    
    data class Failure(val error: Throwable, val exitCode: Int? = null) : ExecutionResult
}

abstract class Task(val name: String) {
    val id: TaskId = TaskId(name)
    val dependencyObjects = mutableSetOf<Task>()
    val dependencyNames = mutableSetOf<String>()
    val inputs = TaskInputs()
    val outputs = TaskOutputs()
    val topologyRequirements = mutableListOf<TopologyRequirement>()

    val registeredProperties = mutableListOf<Property<*>>()

    protected fun <T> property(): Property<T> {
        val prop = Property<T>()
        registeredProperties.add(prop)
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

    fun dependsOn(vararg tasks: Task) {
        dependencyObjects.addAll(tasks)
    }

    fun dependsOn(vararg taskNames: String) {
        dependencyNames.addAll(taskNames)
    }

    fun topology(block: TopologyBuilder.() -> Unit) {
        val builder = TopologyBuilder()
        builder.block()
        topologyRequirements.addAll(builder.requirements)
    }

    abstract suspend fun execute(context: ExecutionContext): ExecutionResult
}

class TopologyBuilder {
    val requirements = mutableListOf<TopologyRequirement>()

    fun localOnly() {
        requirements.add(TopologyRequirement.LocalOnly)
    }

    fun requires(key: String, value: String) {
        requirements.add(TopologyRequirement.RequiresLabel(key, value))
    }

    fun prefers(key: String, value: String) {
        requirements.add(TopologyRequirement.PrefersLabel(key, value))
    }

    fun requiresCapability(capability: String) {
        requirements.add(TopologyRequirement.RequiresCapability(capability))
    }
}
