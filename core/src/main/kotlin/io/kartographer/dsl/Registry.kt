package io.kartographer.dsl

import io.kartographer.core.model.Group
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import java.util.concurrent.ConcurrentHashMap

/**
 * The nodes declared by one pipeline, plus the project settings that pipeline configured.
 *
 * A single JVM can hold several of these at once — the daemon keeps one per cached project —
 * so nothing here may be process-global. [TaskRegistry] picks the instance the DSL writes to.
 */
class NodeRegistry {
    private val nodesMap = ConcurrentHashMap<TaskId, Node>()

    /** Set by the `project { rootDirectory = ... }` DSL block. */
    var projectRoot: String? = null
    val pipelineInputFiles = linkedSetOf<String>()
    var projectDirectory: String? = null

    val nodes: Map<TaskId, Node>
        get() = nodesMap

    fun clear() {
        nodesMap.clear()
        projectRoot = null
        pipelineInputFiles.clear()
        projectDirectory = null
    }

    fun register(node: Node) {
        val id = node.id
        if (nodesMap.containsKey(id)) {
            error("Node with name '${id.value}' is already registered.")
        }
        nodesMap[id] = node
    }

    fun getOrRegisterGroup(name: String): Group {
        val id = TaskId(name)
        val existing = nodesMap[id]
        if (existing != null) {
            if (existing !is Group) {
                error("Cannot register group '$name' because a non-group node with that name already exists.")
            }
            return existing
        }
        val newGroup = Group(name)
        register(newGroup)
        return newGroup
    }

    fun <T : Task<*, *>> create(clazz: Class<T>, name: String, configure: T.() -> Unit): T {
        val task = try {
            val constructor = clazz.getConstructor(String::class.java)
            constructor.newInstance(name)
        } catch (e: NoSuchMethodException) {
            throw IllegalArgumentException(
                "Task type ${clazz.name} must have a public constructor taking a single String argument (the task name).",
                e
            )
        }
        task.configure()
        register(task)
        return task
    }
}

/**
 * Where the pipeline DSL writes.
 *
 * Kept as an `object` so `task("x") { }` in a `.kts` script needs no receiver, but the
 * backing store is whichever [NodeRegistry] is bound to the current thread. A pipeline load
 * wraps script evaluation in [withRegistry]; anything outside a load falls back to a shared
 * default, which is what the CLI and the unit tests have always used.
 *
 * A thread local is sufficient because a script is compiled and evaluated on one thread —
 * `BasicJvmScriptingHost.eval` runs the script body on its caller. A pipeline that registered
 * tasks from a coroutine it dispatched elsewhere would escape the binding; nothing does that
 * today, and [NodeRegistry] is directly addressable for any caller that needs to be explicit.
 */
object TaskRegistry {
    private val default = NodeRegistry()
    private val scoped = ThreadLocal<NodeRegistry?>()

    /** Public because the inline `create` below has to reach it from its call sites. */
    val current: NodeRegistry
        get() = scoped.get() ?: default

    /** Binds [registry] as the DSL target for the duration of [block] on this thread. */
    fun <R> withRegistry(registry: NodeRegistry, block: () -> R): R {
        val previous = scoped.get()
        scoped.set(registry)
        return try {
            block()
        } finally {
            scoped.set(previous)
        }
    }

    val nodes: Map<TaskId, Node>
        get() = current.nodes

    fun clear() = current.clear()

    fun register(node: Node) = current.register(node)

    fun getOrRegisterGroup(name: String): Group = current.getOrRegisterGroup(name)

    inline fun <reified T : Task<*, *>> create(name: String, noinline configure: T.() -> Unit): T =
        current.create(T::class.java, name, configure)
}

inline fun <reified T : Task<*, *>> task(name: String, noinline configure: T.() -> Unit): T {
    return TaskRegistry.create(name, configure)
}

fun group(name: String, block: GroupSpec.() -> Unit): GroupSpec {
    val groupNode = TaskRegistry.getOrRegisterGroup(name)
    val spec = GroupSpec(groupNode)
    spec.block()
    return spec
}

object ProjectConfig {
    var projectRoot: String?
        get() = TaskRegistry.current.projectRoot
        set(value) {
            TaskRegistry.current.projectRoot = value
        }
}

class ProjectSpec {
    var rootDirectory: String?
        get() = ProjectConfig.projectRoot
        set(value) { ProjectConfig.projectRoot = value }
}

fun project(block: ProjectSpec.() -> Unit) {
    val spec = ProjectSpec()
    spec.block()
}

/** Declares a file read while evaluating the pipeline so daemon cache invalidation includes it. */
fun pipelineInputFile(path: String): String {
    val file = java.io.File(path).let { candidate ->
        if (candidate.isAbsolute) candidate
        else TaskRegistry.current.projectDirectory?.let { java.io.File(it, path) } ?: candidate
    }.absoluteFile
    TaskRegistry.current.pipelineInputFiles.add(file.path)
    return file.path
}

/**
 * Resolves [path] against the pipeline's project directory rather than the process working
 * directory, and records it as a pipeline input so its presence is part of cache invalidation.
 *
 * A bare `java.io.File("go.mod")` in a `.kts` would resolve against the daemon's CWD, which is
 * not the project being built. Pipelines should use this helper for project-relative probes.
 */
fun projectFile(path: String): java.io.File = java.io.File(pipelineInputFile(path))
