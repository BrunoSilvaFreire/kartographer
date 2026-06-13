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

    override fun run() {
        val pClass = pipelineClass ?: run {
            echo("Error: Please specify the compiled pipeline class using -p or --pipeline.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        if (pClass.endsWith(".kts")) {
            echo("Error: Dynamic scripting (.kts) loading is a roadmap feature. Please use a compiled class name or see docs/scripting_roadmap.md.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        try {
            // Load and instantiate the compiled pipeline class to populate TaskRegistry
            val clazz = Class.forName(pClass)
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: ClassNotFoundException) {
            echo("Error: Pipeline class '$pClass' not found. Ensure it is compiled and on classpath.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        } catch (e: Exception) {
            echo("Error loading pipeline class '$pClass': ${e.localizedMessage}", err = true)
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

        val scheduler = io.kartographer.core.scheduler.ConcurrentScheduler(tasks)

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

        val result = scheduler.execute(
            targetTask = targetTask,
            context = context,
            checkTopology = { checkTopology(it, worker) },
            onTaskStart = { echo("[INFO] [${it.name}] Started execution...") },
            onTaskSuccess = { echo("[SUCCESS] [${it.name}] Completed successfully.") },
            onTaskFailure = { task, err, _ ->
                echo("[FAILURE] [${task.name}] Failed: ${err.localizedMessage}", err = true)
            }
        )

        when (result) {
            is ExecutionResult.Success -> {
                echo("Pipeline run finished successfully.")
            }
            is ExecutionResult.UpToDate -> {
                echo("Pipeline run finished (up-to-date).")
            }
            is ExecutionResult.Failure -> {
                echo("Pipeline run failed.", err = true)
                throw com.github.ajalt.clikt.core.ProgramResult(result.exitCode ?: 1)
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
