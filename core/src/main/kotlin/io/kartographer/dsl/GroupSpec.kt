package io.kartographer.dsl

import io.kartographer.core.model.Group
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task

class GroupSpec(val group: Group) {
    inline fun <reified T : Task> task(name: String, noinline configure: T.() -> Unit): T {
        val fullyQualifiedName = if (group.name.isEmpty()) name else "${group.name}/$name"
        val task = TaskRegistry.create<T>(fullyQualifiedName, configure)
        group.dependsOn(task)
        return task
    }

    fun group(name: String, block: GroupSpec.() -> Unit): GroupSpec {
        val fullyQualifiedName = if (group.name.isEmpty()) name else "${group.name}/$name"
        val subgroup = TaskRegistry.getOrRegisterGroup(fullyQualifiedName)
        group.dependsOn(subgroup)
        val spec = GroupSpec(subgroup)
        spec.block()
        return spec
    }

    fun excludeTasksFromGroupDefault(vararg taskRefs: Node) {
        group.dependencyObjects.removeAll(taskRefs.toSet())
    }
}
