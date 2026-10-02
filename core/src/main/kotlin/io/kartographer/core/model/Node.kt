package io.kartographer.core.model

import io.kartographer.core.Property
import io.kartographer.core.scheduler.TaskStateStore
import kotlinx.coroutines.sync.Mutex

const val SECRET_MASK = "***"

abstract class Node(val name: String) {
    val id: TaskId = TaskId(name)
    open val executionId: String get() = id.value
    open val lifecycle: TaskLifecycle get() = TaskLifecycle.FINITE
    val dependencyObjects = mutableSetOf<Node>()
    val dependencyNames = mutableSetOf<String>()
    internal val configuredDependencies = mutableListOf<ConfiguredDependency<*, *>>()
    internal val invocationDependencies = mutableSetOf<TaskInvocationRef>()
    internal val completionDependencyObjects = mutableSetOf<Node>()
    internal val completionDependencyNames = mutableSetOf<String>()
    internal val completionInvocationDependencies = mutableSetOf<TaskInvocationRef>()

    // CLI properties live on every node, not just tasks: a group can declare a
    // flag (e.g. `--version` on a push group) that the CLI surfaces and validates
    // even though the group itself has no input set to materialize.
    val registeredProperties = mutableListOf<Property<*>>()

    fun invocationDependencyTasks(): List<Task<*, *>> =
        configuredDependencies.map { it.task } + invocationDependencies.map { it.task }

    fun invocationDependencyNames(): List<String> =
        configuredDependencies.map { it.ref.displayName } + invocationDependencies.map { it.displayName }

    fun dependsOn(vararg nodes: Node) {
        dependencyObjects.addAll(nodes)
    }

    fun <I : InputSet, O : OutputSet> dependsOn(task: Task<I, O>): O {
        dependencyObjects.add(task)
        return task.outputs
    }

    fun <I : InputSet, O : OutputSet> dependsOn(task: Task<I, O>, configure: (I) -> Unit): O {
        val ordinal = configuredDependencies.count { it.task === task } + 1
        val id = "$name->${task.name}#$ordinal"
        val display = "${task.name} @ $name" + if (ordinal == 1) "" else "#$ordinal"
        val ref = TaskInvocationRef(id, display, task, configured = true)
        @Suppress("UNCHECKED_CAST")
        val outputs = task.buildOutputs().also { it.bind(ref) } as O
        configuredDependencies.add(ConfiguredDependency(task, ref, configure, outputs))
        return outputs
    }

    fun <I : InputSet, O : OutputSet> dependsOnCompletion(task: Task<I, O>, configure: (I) -> Unit): O {
        val ordinal = configuredDependencies.count { it.task === task } + 1
        val id = "$name->${task.name}#$ordinal"
        val display = "${task.name} @ $name" + if (ordinal == 1) "" else "#$ordinal"
        val ref = TaskInvocationRef(id, display, task, configured = true)
        @Suppress("UNCHECKED_CAST")
        val outputs = task.buildOutputs().also { it.bind(ref) } as O
        configuredDependencies.add(ConfiguredDependency(task, ref, configure, outputs, forceCompletion = true))
        return outputs
    }

    fun dependsOn(outputs: OutputSet) {
        val ref = outputs.invocation
            ?: throw IllegalArgumentException("OutputSet is not bound to a task invocation.")
        invocationDependencies.add(ref)
    }

    fun dependsOn(vararg nodeNames: String) {
        dependencyNames.addAll(nodeNames)
    }

    fun dependsOnCompletion(vararg nodes: Node) {
        completionDependencyObjects.addAll(nodes)
    }

    fun dependsOnCompletion(vararg nodeNames: String) {
        completionDependencyNames.addAll(nodeNames)
    }

    fun dependsOnCompletion(outputs: OutputSet) {
        val ref = outputs.invocation
            ?: throw IllegalArgumentException("OutputSet is not bound to a task invocation.")
        completionInvocationDependencies.add(ref)
    }
}

// A Step in the DAG that doesn't change state.
abstract class Validation(name: String) : Node(name)

// Infers an action that is executed
abstract class Task<I : InputSet, O : OutputSet>(name: String) : Node(name) {
    val inputs = TaskInputs()
    val trackedOutputs = TaskOutputs()
    val topologyRequirements = mutableListOf<TopologyRequirement>()

    open val requiresElevation: Boolean get() = false
    internal val inputBuildMutex = Mutex()

    // Resources this task must hold to run. Order is irrelevant — the scheduler acquires
    // by name so that two tasks wanting the same pair cannot deadlock on each other.
    val semaphores = linkedSetOf<SemaphoreHandle>()
    final override var lifecycle: TaskLifecycle = TaskLifecycle.FINITE
        private set
    var serviceSpec: ServiceSpec? = null
        private set

    fun service(configure: ServiceSpec.() -> Unit) {
        check(serviceSpec == null) { "Task '$name' is already configured as a service." }
        serviceSpec = ServiceSpec().apply(configure).validated()
        lifecycle = TaskLifecycle.SERVICE
        alwaysRun()
        topology { localOnly() }
    }

    /** Declares that this task holds [handles] while it runs. */
    fun acquires(vararg handles: SemaphoreHandle) {
        semaphores.addAll(handles)
    }

    /** [acquires] for a task type that knows its own conflict without the pipeline saying so. */
    fun acquires(name: String, permits: Int = 1) {
        semaphores.add(SemaphoreHandle(name, permits))
    }

    /**
     * Which of [semaphores] this particular invocation actually needs.
     *
     * A task type that declares a resource it only sometimes touches narrows it here — the
     * conflict may depend on how the task was configured, and a task that queues for a
     * resource it will not use is just serialisation with extra steps. The declared set
     * still sizes the run's semaphores, so overriding this cannot invent a new one.
     */
    open fun semaphoresToAcquire(inputSet: I): Collection<SemaphoreHandle> = semaphores

    @Suppress("UNCHECKED_CAST")
    fun semaphoresToAcquireAny(inputSet: InputSet): Collection<SemaphoreHandle> =
        semaphoresToAcquire(inputSet as I)

    abstract suspend fun buildInputs(context: ExecutionContext): I
    abstract fun buildOutputs(): O
    abstract suspend fun execute(context: ExecutionContext, inputSet: I): ExecutionResult<O>

    val outputs: O by lazy {
        val ref = TaskInvocationRef(name, name, this, configured = false)
        @Suppress("UNCHECKED_CAST")
        (buildOutputs().also { it.bind(ref) } as O)
    }

    suspend fun buildInputsAny(context: ExecutionContext): InputSet = buildInputs(context)
    fun buildOutputsAny(): OutputSet = buildOutputs()

    @Suppress("UNCHECKED_CAST")
    suspend fun executeAny(context: ExecutionContext, inputSet: InputSet): ExecutionResult<out OutputSet> =
        execute(context, inputSet as I)

    protected fun success(configure: O.() -> Unit = {}): ExecutionResult<O> =
        ExecutionResult.Success(buildOutputs().apply(configure))

    protected fun failure(
        error: Throwable,
        exitCode: Int? = null,
        configure: O.() -> Unit = {}
    ): ExecutionResult<O> = ExecutionResult.Failure(error, exitCode, buildOutputs().apply(configure))

    protected fun <T> property(initBlock: (Property<T>.() -> Unit)? = null): Property<T> {
        val prop = Property<T>()
        registeredProperties.add(prop)
        initBlock?.invoke(prop)
        return prop
    }


    val upToDateChecks = mutableListOf<io.kartographer.core.scheduler.UpToDateCheck>()

    val customFilesToTrack = mutableSetOf<String>()
    val customPropertiesToTrack = mutableMapOf<String, () -> Any?>()

    // Set by `alwaysRun()`. Kept separate from `upToDateChecks` so `karto graph` can report
    // the intent without having to run the checks.
    var neverUpToDate = false
        private set

    /**
     * Lets reporting/finalizer tasks inspect outputs even when an upstream task failed.
     * Such a task owns the final outcome and must return Failure when the run should fail.
     */
    var runsAfterDependencyFailure = false
        private set

    fun runAfterFailedDependencies() {
        runsAfterDependencyFailure = true
    }

    // Declares that this task has no cacheable result and must run on every invocation.
    fun alwaysRun() {
        neverUpToDate = true
    }

    // True when nothing about this task can be verified between runs.
    val alwaysRuns: Boolean
        get() = neverUpToDate || !hasVerifiableState

    // Properties are deliberately excluded: a spider re-crawls the same URL with the same
    // arguments every time, so an unchanged property map proves nothing about the result.
    private val hasVerifiableState: Boolean
        get() = upToDateChecks.isNotEmpty() ||
                inputs.files.isNotEmpty() || inputs.dirs.isNotEmpty() ||
                trackedOutputs.files.isNotEmpty() || trackedOutputs.dirs.isNotEmpty() ||
                customFilesToTrack.isNotEmpty() || customPropertiesToTrack.isNotEmpty()

    // What this task can re-check against a dependency that just produced fresh results.
    private val canReverify: Boolean
        get() = inputs.files.isNotEmpty() || inputs.dirs.isNotEmpty() ||
                customFilesToTrack.isNotEmpty()

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

    suspend fun isUpToDate(context: ExecutionContext, dependencyRan: Boolean = false): Boolean {
        if (neverUpToDate) return false
        if (outputs.schema().values.any { it.secret }) return false
        if (!hasVerifiableState) return false
        // A dependency produced fresh results, so our last state may describe stale inputs.
        // Only skip if we have something concrete to re-verify against.
        if (dependencyRan && !canReverify) return false

        if (upToDateChecks.isNotEmpty()) {
            return upToDateChecks.all { it.isUpToDate(context, this) }
        }

        if (context.inputHash != null && TaskStateStore.loadState(context, this)?.inputHash != context.inputHash) {
            return false
        }

        val helpers = io.kartographer.core.scheduler.UpToDateHelpers
        return helpers.propertiesMatch().isUpToDate(context, this) &&
               helpers.inputsMatch().isUpToDate(context, this) &&
               helpers.outputsMatch().isUpToDate(context, this) &&
               helpers.customPropertiesMatch().isUpToDate(context, this) &&
               helpers.customFilesMatch().isUpToDate(context, this)
    }

    fun topology(block: TopologyBuilder.() -> Unit) {
        val builder = TopologyBuilder()
        builder.block()
        topologyRequirements.addAll(builder.requirements)
    }
}
