import io.kartographer.cli.render.graph.ForceDirectedLayout
import io.kartographer.core.model.graph.AdjacencyList
import io.kartographer.core.model.Task
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult

class DummyTask(override val name: String, override val topologyRequirements: List<io.kartographer.core.model.TopologyRequirement> = emptyList()) : Task {
    override val dependencyNames: Set<String> = emptySet()
    override val dependencyObjects: Set<Task> = emptySet()
    override suspend fun execute(context: ExecutionContext) = ExecutionResult.Success
    override suspend fun isUpToDate(context: ExecutionContext) = false
}

fun main() {
    val graph = AdjacencyList<Task, Unit>()
    val t1 = graph.push(DummyTask("t1"))
    val t2 = graph.push(DummyTask("t2"))
    graph.connect(t1, t2, Unit)
    
    val layout = ForceDirectedLayout(graph)
    layout.computeLayout()
    println(layout.renderEdgesToStrings())
}
