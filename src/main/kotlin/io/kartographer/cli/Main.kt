package io.kartographer.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult
import io.kartographer.core.Task
import io.kartographer.core.TaskId
import io.kartographer.core.TopologyRequirement
import io.kartographer.core.Worker
import io.kartographer.core.WorkerId
import io.kartographer.core.scheduler.DagScheduler
import io.kartographer.dsl.TaskRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

class KartographerCommand : CliktCommand(name = "kartographer", invokeWithoutSubcommand = false) {
    val pipelineClass by option("-p", "--pipeline", help = "Compiled pipeline class name to load")
        .default("io.kartographer.example.BasicPipeline")

    override fun run() {
        if (pipelineClass.endsWith(".kts")) {
            echo("Error: Dynamic scripting (.kts) loading is a roadmap feature. Please use a compiled class name (e.g., 'io.kartographer.example.BasicPipeline') or see docs/scripting_roadmap.md.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        try {
            // Load and instantiate the compiled pipeline class to populate TaskRegistry
            val clazz = Class.forName(pipelineClass)
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: ClassNotFoundException) {
            echo("Error: Pipeline class '$pipelineClass' not found. Ensure it is compiled and on classpath.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        } catch (e: Exception) {
            echo("Error loading pipeline class '$pipelineClass': ${e.localizedMessage}", err = true)
            e.printStackTrace()
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
    }
}

class TasksCommand : CliktCommand(name = "tasks", help = "List all registered tasks in the pipeline") {
    override fun run() {
        val tasks = TaskRegistry.tasks
        if (tasks.isEmpty()) {
            echo("No tasks registered.")
            return
        }
        echo("Registered tasks:")
        for ((id, task) in tasks) {
            val typeName = task::class.simpleName ?: "Task"
            echo("- ${id.value} ($typeName)")
        }
    }
}

class GraphCommand : CliktCommand(name = "graph", help = "Display the task dependency graph") {
    override fun run() {
        val tasks = TaskRegistry.tasks
        if (tasks.isEmpty()) {
            echo("No tasks registered.")
            return
        }
        val scheduler = DagScheduler(tasks)
        try {
            val resolvedDeps = scheduler.resolveDependencies()
            echo("Task Dependency Graph:")
            for (task in resolvedDeps.keys) {
                val deps = resolvedDeps[task] ?: emptySet()
                if (deps.isEmpty()) {
                    echo("  ${task.name}")
                } else {
                    echo("  ${task.name} -> [${deps.joinToString { it.name }}]")
                }
            }
        } catch (e: Exception) {
            echo("Error validating graph: ${e.localizedMessage}", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
    }
}

class RunCommand : CliktCommand(name = "run", help = "Run a task and all its dependencies") {
    val taskName by argument(help = "The name of the task to run")

    override fun run() = runBlocking {
        val tasks = TaskRegistry.tasks
        val targetId = TaskId(taskName)
        val targetTask = tasks[targetId]
        if (targetTask == null) {
            echo("Error: Task '$taskName' not found.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        val scheduler = DagScheduler(tasks)
        val executionOrder = try {
            scheduler.getExecutionOrder()
        } catch (e: Exception) {
            echo("Error resolving execution order: ${e.localizedMessage}", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        // Filter executionOrder to execute only the target task and its transitive dependencies
        val tasksToExecute = getTransitiveDependencies(targetTask) + targetTask
        val orderedExecution = executionOrder.filter { it in tasksToExecute }

        echo("Executing tasks: ${orderedExecution.joinToString(" -> ") { it.name }}")

        // Define a simple local worker for the MVP
        val worker = Worker(
            id = WorkerId("local-worker"),
            labels = mapOf(
                "arch" to System.getProperty("os.arch"),
                "os" to System.getProperty("os.name")
            ),
            capabilities = setOf("local-exec")
        )
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        for (task in orderedExecution) {
            echo(">>> Running task '${task.name}'...")
            
            // Check topology placement requirements
            val placementOk = checkTopology(task, worker)
            if (!placementOk) {
                echo("Error: Task '${task.name}' topology requirements not satisfied by worker.", err = true)
                throw com.github.ajalt.clikt.core.ProgramResult(1)
            }

            when (val result = task.execute(context)) {
                is ExecutionResult.Success -> {
                    echo(">>> Task '${task.name}' finished successfully.")
                }
                is ExecutionResult.Failure -> {
                    echo(">>> Task '${task.name}' failed: ${result.error.localizedMessage}", err = true)
                    throw com.github.ajalt.clikt.core.ProgramResult(result.exitCode ?: 1)
                }
            }
        }
    }

    private fun getTransitiveDependencies(task: Task): Set<Task> {
        val deps = mutableSetOf<Task>()
        fun collect(t: Task) {
            for (dep in t.dependencyObjects) {
                if (deps.add(dep)) {
                    collect(dep)
                }
            }
            val allTasks = TaskRegistry.tasks
            for (depName in t.dependencyNames) {
                val depTask = allTasks[TaskId(depName)]
                if (depTask != null && deps.add(depTask)) {
                    collect(depTask)
                }
            }
        }
        collect(task)
        return deps
    }

    private fun checkTopology(task: Task, worker: Worker): Boolean {
        for (req in task.topologyRequirements) {
            when (req) {
                is TopologyRequirement.LocalOnly -> {
                    // Always satisfies locally
                }
                is TopologyRequirement.RequiresLabel -> {
                    val value = worker.labels[req.key]
                    if (value != req.value) return false
                }
                is TopologyRequirement.RequiresCapability -> {
                    if (!worker.capabilities.contains(req.capability)) return false
                }
                is TopologyRequirement.PrefersLabel -> {
                    // Prefers are preferences, they do not block execution
                }
            }
        }
        return true
    }
}

fun main(args: Array<String>) {
    KartographerCommand()
        .subcommands(TasksCommand(), GraphCommand(), RunCommand())
        .main(args)
}
