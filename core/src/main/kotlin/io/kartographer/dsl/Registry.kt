package io.kartographer.dsl

import io.kartographer.core.Task
import io.kartographer.core.TaskId
import java.util.concurrent.ConcurrentHashMap

object TaskRegistry {
    private val tasksMap = ConcurrentHashMap<TaskId, Task>()

    val tasks: Map<TaskId, Task>
        get() = tasksMap

    fun clear() {
        tasksMap.clear()
    }

    fun register(task: Task) {
        val id = task.id
        if (tasksMap.containsKey(id)) {
            error("Task with name '${id.value}' is already registered.")
        }
        tasksMap[id] = task
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
