package io.kartographer.daemon.project

import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskLifecycle
import io.kartographer.core.scheduler.describeInvocationGraph
import io.kartographer.daemon.proto.DescribeProjectResponse
import io.kartographer.daemon.proto.describeProjectResponse
import io.kartographer.daemon.proto.nodeInfo
import io.kartographer.daemon.proto.propertyInfo
import io.kartographer.daemon.proto.resolvedNode
import io.kartographer.daemon.proto.resolvedDependency

/**
 * Flattens a loaded pipeline into the wire form `karto tasks`, `karto graph` and
 * `karto run <task> --help` all render from.
 *
 * One RPC serves all three because they need the same load; only the formatting differs, and
 * that stays in the CLI where the terminal is.
 */
object ProjectDescription {

    fun of(project: LoadedProject): DescribeProjectResponse {
        val nodes = project.nodes

        return describeProjectResponse {
            project.declaredProjectRoot?.let { projectRoot = it }

            for ((id, node) in nodes.entries.sortedBy { it.key.value }) {
                this.nodes.add(
                    nodeInfo {
                        name = id.value
                        type = node::class.simpleName ?: "Node"
                        // As declared, not resolved — this is what the `tasks` table shows.
                        dependencies.addAll(
                            node.dependencyNames + node.dependencyObjects.map { it.name } +
                                node.invocationDependencyNames()
                        )
                        isTask = node is Task<*, *>
                        if (node is Task<*, *>) {
                            requiresElevation = node.requiresElevation
                            alwaysRuns = node.alwaysRuns
                            lifecycle = when (node.lifecycle) {
                                TaskLifecycle.FINITE -> io.kartographer.daemon.proto.TaskLifecycle.TASK_FINITE
                                TaskLifecycle.SERVICE -> io.kartographer.daemon.proto.TaskLifecycle.TASK_SERVICE
                            }
                        }
                        properties.addAll(propertiesOf(node))
                    }
                )
            }

            // Resolution can legitimately fail (cycle, missing dependency). `tasks` must still
            // render in that case, so the failure travels as a field rather than an RPC error.
            try {
                val resolved = describeInvocationGraph(nodes)
                for (node in resolved) {
                    graph.add(
                        resolvedNode {
                            name = node.displayName
                            invocationId = node.invocationId
                            displayName = node.displayName
                            lifecycle = if (node.lifecycle == TaskLifecycle.SERVICE)
                                io.kartographer.daemon.proto.TaskLifecycle.TASK_SERVICE
                            else io.kartographer.daemon.proto.TaskLifecycle.TASK_FINITE
                            dependencies.addAll(node.dependencies.map { dependency ->
                                resolvedDependency {
                                    invocationId = dependency.invocationId
                                    displayName = dependency.displayName
                                    condition = when (dependency.condition) {
                                        io.kartographer.core.model.DependencyCondition.READY ->
                                            io.kartographer.daemon.proto.DependencyCondition.DEPENDENCY_READY
                                        io.kartographer.core.model.DependencyCondition.COMPLETED ->
                                            io.kartographer.daemon.proto.DependencyCondition.DEPENDENCY_COMPLETED
                                    }
                                }
                            })
                        }
                    )
                }
            } catch (e: Exception) {
                graphError = e.localizedMessage ?: e.toString()
            }
        }
    }

    private fun propertiesOf(node: Node) = when (node) {
        is Task<*, *> -> TaskProperties.declaredOn(node).map { declared ->
            val property = declared.property
            propertyInfo {
                name = declared.fieldName
                property.cliName?.let { cliName = it }
                property.cliDescription?.let { description = it }
                typeName = TaskProperties.typeNameOf(declared)
                property.literalCompletions?.let { literalCompletions.addAll(it) }
                hasDynamicCompletions = property.literalCompletions == null && property.completionProvider != null
            }
        }
        else -> node.registeredProperties.map { property ->
            propertyInfo {
                name = property.cliName ?: "property"
                property.cliName?.let { cliName = it }
                property.cliDescription?.let { description = it }
                typeName = property.typeName ?: "Unknown"
                property.literalCompletions?.let { literalCompletions.addAll(it) }
                hasDynamicCompletions = property.literalCompletions == null && property.completionProvider != null
            }
        }
    }

    /** The nodes `run` would execute for [targetName], for the pre-run elevation check. */
    fun executionSet(project: LoadedProject, target: Node): Set<Node> =
        Nodes.closure(target, project.nodes)
}
