package io.kartographer.daemon.project

import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.scheduler.DagScheduler
import io.kartographer.daemon.proto.DescribeProjectResponse
import io.kartographer.daemon.proto.describeProjectResponse
import io.kartographer.daemon.proto.nodeInfo
import io.kartographer.daemon.proto.propertyInfo
import io.kartographer.daemon.proto.resolvedNode

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
                        dependencies.addAll(node.dependencyNames + node.dependencyObjects.map { it.name })
                        isTask = node is Task
                        if (node is Task) {
                            requiresElevation = node.requiresElevation
                            properties.addAll(propertiesOf(node))
                        }
                    }
                )
            }

            // Resolution can legitimately fail (cycle, missing dependency). `tasks` must still
            // render in that case, so the failure travels as a field rather than an RPC error.
            try {
                val resolved = DagScheduler(nodes).resolveDependencies()
                for ((node, deps) in resolved) {
                    graph.add(
                        resolvedNode {
                            name = node.name
                            dependencies.addAll(deps.map { it.name })
                        }
                    )
                }
            } catch (e: Exception) {
                graphError = e.localizedMessage ?: e.toString()
            }
        }
    }

    private fun propertiesOf(task: Task) = TaskProperties.declaredOn(task).map { declared ->
        val property = declared.property
        propertyInfo {
            name = declared.fieldName
            property.cliName?.let { cliName = it }
            property.cliDescription?.let { description = it }
            typeName = TaskProperties.typeNameOf(declared)
            property.literalCompletions?.let { literalCompletions.addAll(it) }
            // Dynamic providers are arbitrary suspend code held by the task, so they can only
            // be evaluated here on the daemon — the CLI asks for them via Complete.
            hasDynamicCompletions = property.literalCompletions == null && property.completionProvider != null
        }
    }

    /** The nodes `run` would execute for [targetName], for the pre-run elevation check. */
    fun executionSet(project: LoadedProject, target: Node): Set<Node> =
        Nodes.closure(target, project.nodes)
}
