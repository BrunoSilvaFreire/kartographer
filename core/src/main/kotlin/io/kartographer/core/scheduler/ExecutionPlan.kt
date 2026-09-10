package io.kartographer.core.scheduler

import io.kartographer.core.model.ConfiguredDependency
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.Node
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.core.model.TaskInvocationRef

internal class InvocationNode(
    val ref: TaskInvocationRef,
    val task: Task<*, *>,
    val configuredDependency: ConfiguredDependency<*, *>? = null
) : Node(ref.displayName)
{
    override val executionId: String get() = ref.id
}

internal data class ExecutionPlan(
    val target: Node,
    val nodes: Set<Node>,
    val dependencies: Map<Node, Set<Node>>,
    val invocations: Map<String, InvocationNode>
)

data class InvocationGraphNode(
    val invocationId: String,
    val displayName: String,
    val dependencies: List<String>
)

fun describeInvocationGraph(registry: Map<TaskId, Node>): List<InvocationGraphNode> {
    val builder = ExecutionPlanBuilder(registry)
    val described = linkedMapOf<String, InvocationGraphNode>()
    registry.values.sortedBy { it.name }.forEach { target ->
        val plan = builder.build(target)
        plan.nodes.forEach { node ->
            described[node.executionId] = InvocationGraphNode(
                node.executionId,
                node.name,
                plan.dependencies[node].orEmpty().map { it.executionId }.sorted()
            )
        }
    }
    return described.values.map { node ->
        node.copy(dependencies = node.dependencies.map { described[it]?.displayName ?: it })
    }.sortedBy { it.displayName }
}

internal class ExecutionPlanBuilder(private val registry: Map<TaskId, Node>) {
    private val defaults = mutableMapOf<Task<*, *>, InvocationNode>()
    private val configured = mutableMapOf<String, InvocationNode>()
    private val dependencies = linkedMapOf<Node, Set<Node>>()

    fun build(target: Node): ExecutionPlan {
        val targetNode = materialize(target)
        val closure = linkedSetOf<Node>()

        fun visit(node: Node, path: MutableList<Node>) {
            if (node in path) {
                val cycle = (path.dropWhile { it !== node } + node).map { it.name }
                throw CycleDetectedException(cycle)
            }
            if (!closure.add(node)) return
            path.add(node)
            dependenciesFor(node).forEach { visit(it, path) }
            path.removeAt(path.lastIndex)
        }

        visit(targetNode, mutableListOf())
        val selected = dependencies.filterKeys { it in closure }
            .mapValues { (_, deps) -> deps.filterTo(linkedSetOf()) { it in closure } }
        return ExecutionPlan(targetNode, closure, selected, defaults.values.associateBy { it.ref.id } + configured)
    }

    private fun materialize(node: Node): Node = when (node) {
        is Task<*, *> -> defaultInvocation(node)
        else -> node
    }

    private fun defaultInvocation(task: Task<*, *>): InvocationNode = defaults.getOrPut(task) {
        InvocationNode(TaskInvocationRef(task.name, task.name, task, configured = false), task)
    }

    private fun configuredInvocation(dependency: ConfiguredDependency<*, *>): InvocationNode =
        configured.getOrPut(dependency.ref.id) {
            InvocationNode(dependency.ref, dependency.task, dependency)
        }

    private fun invocation(ref: TaskInvocationRef): InvocationNode = if (!ref.configured) {
        defaultInvocation(ref.task)
    } else {
        configured[ref.id]
            ?: registry.values.asSequence()
                .flatMap { it.configuredDependencies.asSequence() }
                .firstOrNull { it.ref.id == ref.id }
                ?.let(::configuredInvocation)
            ?: throw MissingDependencyException(ref.displayName, ref.id)
    }

    private fun dependenciesFor(node: Node): Set<Node> = dependencies.getOrPut(node) {
        val declaration = if (node is InvocationNode) node.task else node
        linkedSetOf<Node>().apply {
            declaration.dependencyObjects.forEach { add(materialize(it)) }
            declaration.dependencyNames.forEach { name ->
                add(materialize(registry[TaskId(name)] ?: throw MissingDependencyException(declaration.name, name)))
            }
            declaration.configuredDependencies.forEach { add(configuredInvocation(it)) }
            declaration.invocationDependencies.forEach { add(invocation(it)) }

            if (declaration is Task<*, *>) {
                declaration.registeredProperties.forEach { property ->
                    property.producingInvocation?.let { add(invocation(it)) }
                        ?: property.producingTask?.let { add(defaultInvocation(it)) }
                }
            }
        }
    }
}
