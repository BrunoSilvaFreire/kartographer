package io.kartographer.dsl

import io.kartographer.core.model.Group
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import java.util.concurrent.ConcurrentHashMap

object TaskRegistry {
    private val nodesMap = ConcurrentHashMap<TaskId, Node>()

    val nodes: Map<TaskId, Node>
        get() = nodesMap

    fun clear() {
        nodesMap.clear()
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

    inline fun <reified T : Task> create(name: String, configure: T.() -> Unit): T {
        val clazz = T::class.java
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

inline fun <reified T : Task> task(name: String, noinline configure: T.() -> Unit): T {
    return TaskRegistry.create(name, configure)
}

fun group(name: String, block: GroupSpec.() -> Unit): GroupSpec {
    val groupNode = TaskRegistry.getOrRegisterGroup(name)
    val spec = GroupSpec(groupNode)
    spec.block()
    return spec
}

object ProjectConfig {
    var projectRoot: String? = null
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
