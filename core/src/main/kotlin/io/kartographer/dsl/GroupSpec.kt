package io.kartographer.dsl

import io.kartographer.core.Property
import io.kartographer.core.model.Group
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task

class GroupSpec(val group: Group) {
    inline fun <reified T : Task<*, *>> task(name: String, noinline configure: T.() -> Unit): T {
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

    /**
     * Declares a CLI property on this group, so `karto run <group> --flag=value`
     * is discoverable and validatable. The property is descriptive only: the
     * value itself is applied to the member tasks that declare the same CLI flag
     * (see TaskProperties.materialize), not to the group.
     */
    inline fun <reified T> property(noinline configure: Property<T>.() -> Unit = {}): Property<T> {
        val prop = Property<T>().apply(configure)
        prop.typeName = T::class.simpleName
        group.registeredProperties.add(prop)
        return prop
    }

    fun excludeTasksFromGroupDefault(vararg taskRefs: Node) {
        group.dependencyObjects.removeAll(taskRefs.toSet())
        group.configuredDependencies.removeAll { it.task in taskRefs }
    }

    fun <I : io.kartographer.core.model.InputSet, O : io.kartographer.core.model.OutputSet> dependsOn(
        task: Task<I, O>,
        configure: (I) -> Unit
    ): O = group.dependsOn(task, configure)

    fun <I : io.kartographer.core.model.InputSet, O : io.kartographer.core.model.OutputSet> dependsOn(
        task: Task<I, O>
    ): O = group.dependsOn(task)
}
