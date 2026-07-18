package io.kartographer.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import io.kartographer.cli.render.MosaicRenderer
import io.kartographer.cli.render.PlainRenderer
import io.kartographer.cli.render.Renderer
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.core.model.TopologyRequirement
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import io.kartographer.core.scheduler.DagScheduler
import io.kartographer.dsl.TaskRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

class KartographerCommand : CliktCommand(name = "kartographer", invokeWithoutSubcommand = false) {
    val pipelineClass by option("-p", "--pipeline", help = "Compiled pipeline class name or .kts script path to load")
    val projectDir by option("-d", "--project-dir", help = "Project directory override")

    override fun run() {
        currentContext.obj = projectDir

        val dir = projectDir?.let { java.nio.file.Paths.get(it).toFile() } ?: java.io.File(".")
        
        val pClass = pipelineClass ?: run {
            val defaultScript = java.io.File(dir, "karto.kts")
            if (defaultScript.exists()) {
                defaultScript.absolutePath
            } else {
                echo("Error: Default pipeline script 'karto.kts' not found. Please specify the compiled pipeline class or .kts script using -p or --pipeline.", err = true)
                throw com.github.ajalt.clikt.core.ProgramResult(1)
            }
        }

        if (pClass.endsWith(".kts")) {
            try {
                KtsPipelineLoader().load(pClass)
            } catch (e: Exception) {
                echo("Error loading pipeline script '$pClass': ${e.localizedMessage}", err = true)
                throw com.github.ajalt.clikt.core.ProgramResult(1)
            }
        } else {
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
}

class TasksCommand : CliktCommand(name = "tasks", help = "List all registered nodes in the pipeline") {
    override fun run() {
        val nodes = TaskRegistry.nodes
        if (nodes.isEmpty()) {
            echo("No tasks registered.")
            return
        }
        
        fun getCliArgs(node: io.kartographer.core.model.Node): String {
            if (node !is Task) return "-"
            val props = node.registeredProperties.mapNotNull { it.cliName }
            return if (props.isEmpty()) "-" else props.joinToString(", ") { "--$it" }
        }

        val maxFullNameLen = maxOf(14, nodes.keys.maxOfOrNull { it.value.length } ?: 14)
        val maxNameLen = maxOf(9, nodes.keys.maxOfOrNull { it.value.substringAfterLast('/').length } ?: 9)
        val maxTypeLen = maxOf(4, nodes.values.maxOfOrNull { (it::class.simpleName ?: "Node").length } ?: 4)
        val maxCliArgsLen = maxOf(8, nodes.values.maxOfOrNull { getCliArgs(it).length } ?: 8)

        val fullNameHeader = "FULL TASK NAME".padEnd(maxFullNameLen)
        val nameHeader = "TASK NAME".padEnd(maxNameLen)
        val typeHeader = "TYPE".padEnd(maxTypeLen)
        val cliHeader = "CLI ARGS".padEnd(maxCliArgsLen)
        
        echo("$fullNameHeader   $nameHeader   $typeHeader   $cliHeader   DEPENDENCIES")
        echo("-".repeat(maxFullNameLen + maxNameLen + maxTypeLen + maxCliArgsLen + 23))
        
        val sortedNodes = nodes.entries.sortedBy { it.key.value }

        for ((id, node) in sortedNodes) {
            val typeName = (node::class.simpleName ?: "Node").padEnd(maxTypeLen)
            val fullName = id.value.padEnd(maxFullNameLen)
            val shortName = id.value.substringAfterLast('/').padEnd(maxNameLen)
            val cliArgs = getCliArgs(node).padEnd(maxCliArgsLen)
            val deps = node.dependencyNames + node.dependencyObjects.map { it.name }
            val depsStr = if (deps.isEmpty()) "-" else deps.joinToString(", ")
            
            echo("$fullName   $shortName   $typeName   $cliArgs   $depsStr")
        }
    }
}

class GraphCommand : CliktCommand(name = "graph", help = "Display the task dependency graph") {
    override fun run() {
        val nodes = TaskRegistry.nodes
        if (nodes.isEmpty()) {
            echo("No tasks registered.")
            return
        }
        val scheduler = DagScheduler(nodes)
        try {
            val resolvedDeps = scheduler.resolveDependencies()
            echo("Task Dependency Graph:")
            for (node in resolvedDeps.keys) {
                val deps = resolvedDeps[node] ?: emptySet()
                if (deps.isEmpty()) {
                    echo("  ${node.name}")
                } else {
                    echo("  ${node.name} -> [${deps.joinToString { it.name }}]")
                }
            }
        } catch (e: Exception) {
            echo("Error validating graph: ${e.localizedMessage}", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
    }
}

class RunCommand : CliktCommand(
    name = "run",
    help = "Run a task and all its dependencies"
) {
    val taskHelp by option("--task-help", hidden = true).flag()
    val taskName by argument(help = "The name of the task to run").optional()
    val properties by option("-P").associate()
    val ui by option("--ui", help = "Renderer to use (auto, tui, plain)").default("auto")

    override fun run() = runBlocking {
        if (taskHelp && taskName == null) {
            echo(getFormattedHelp())
            return@runBlocking
        }
        
        if (taskName == null) {
            echo(getFormattedHelp(), err = true)
            echo("Error: Missing argument 'taskname'", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        val nodes = TaskRegistry.nodes
        val targetId = TaskId(taskName!!)
        val targetNode = nodes[targetId]
        if (targetNode == null) {
            echo("Error: Node '${taskName}' not found.", err = true)
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }

        if (taskHelp) {
            echo("Task: ${targetNode.name}")
            if (targetNode is Task) {
                val propFields = generateSequence(targetNode::class.java as Class<*>) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter { io.kartographer.core.Property::class.java.isAssignableFrom(it.type) }
                    .toList()
                
                if (propFields.isNotEmpty()) {
                    echo("Properties:")
                    propFields.forEach { field ->
                        field.isAccessible = true
                        val prop = field.get(targetNode) as io.kartographer.core.Property<*>
                        val propName = field.name
                        
                        val genericType = field.genericType
                        val typeName = if (genericType is java.lang.reflect.ParameterizedType) {
                            val typeArg = genericType.actualTypeArguments.firstOrNull()
                            (typeArg as? Class<*>)?.simpleName ?: typeArg.toString().substringAfterLast('.')
                        } else {
                            "Unknown"
                        }
                        
                        val cliOpt = prop.cliName?.let { "--$it" } ?: "(internal, no CLI flag)"
                        var out = "  • $propName: $typeName"
                        out += "\n      CLI Flag: $cliOpt"
                        if (!prop.cliDescription.isNullOrBlank()) {
                            out += "\n      Description: ${prop.cliDescription}"
                        }
                        if (prop.literalCompletions != null) {
                            out += "\n      Accepted Values: ${prop.literalCompletions!!.joinToString(", ")}"
                        } else if (prop.completionProvider != null) {
                            out += "\n      Accepted Values: (Dynamic)"
                        }
                        echo(out)
                    }
                }
            } else {
                echo("Description: Task group containing ${targetNode.dependencyObjects.size + targetNode.dependencyNames.size} direct dependencies.")
            }
            
            val deps = targetNode.dependencyNames + targetNode.dependencyObjects.map { it.name }
            if (deps.isNotEmpty()) {
                echo("Dependencies: ${deps.joinToString(", ")}")
            }
            return@runBlocking
        }

        val scheduler = io.kartographer.core.scheduler.ConcurrentScheduler(nodes)

        // Define a simple local worker for the MVP
        val worker = Worker(
            id = WorkerId("local-worker"),
            labels = mapOf(
                "arch" to System.getProperty("os.arch"),
                "os" to System.getProperty("os.name")
            ),
            capabilities = setOf("local-exec", "ansible", "flutter", "go", "protoc", "pulumi", "python", "docker")
        )
        val pDirStr = currentContext.obj as? String
        var pDir = if (pDirStr != null) Paths.get(pDirStr).toAbsolutePath() else Paths.get("").toAbsolutePath()
        if (pDirStr == null && io.kartographer.dsl.ProjectConfig.projectRoot != null) {
            pDir = pDir.resolve(io.kartographer.dsl.ProjectConfig.projectRoot!!).normalize().toAbsolutePath()
        }
        val isTty = System.console() != null && System.getenv("TERM") != "dumb"
        val renderer: Renderer = when (ui) {
            "tui" -> MosaicRenderer()
            "plain" -> PlainRenderer()
            else -> PlainRenderer() // Defaulting to PlainRenderer as MVP TUI for now
        }

        val context = ExecutionContext(
            projectDir = pDir,
            worker = worker,
            observer = renderer,
            prompter = io.kartographer.local.ConsoleUserPrompter(observer = renderer)
        )

        // Pre-prompt for sudo if any tasks require it!
        val executionSet = mutableSetOf<io.kartographer.core.model.Node>()
        fun collect(n: io.kartographer.core.model.Node) {
            for (dep in n.dependencyObjects) {
                if (executionSet.add(dep)) collect(dep)
            }
            val allNodes = TaskRegistry.nodes
            for (depName in n.dependencyNames) {
                val depNode = allNodes[io.kartographer.core.model.TaskId(depName)]
                if (depNode != null && executionSet.add(depNode)) collect(depNode)
            }
        }
        if (targetNode != null) {
            executionSet.add(targetNode)
            collect(targetNode)
        }

        // Apply CLI properties
        val allProperties = executionSet.filterIsInstance<Task>().flatMap { it.registeredProperties }.filter { it.cliName != null }
        
        val propertyTypeMap = mutableMapOf<io.kartographer.core.Property<*>, Class<*>>()
        executionSet.filterIsInstance<Task>().forEach { task ->
            val propFields = generateSequence(task::class.java as Class<*>) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { io.kartographer.core.Property::class.java.isAssignableFrom(it.type) }
            
            for (field in propFields) {
                field.isAccessible = true
                val prop = field.get(task) as? io.kartographer.core.Property<*>
                if (prop != null) {
                    val genericType = field.genericType
                    if (genericType is java.lang.reflect.ParameterizedType) {
                        val typeArg = genericType.actualTypeArguments.firstOrNull() as? Class<*>
                        if (typeArg != null) {
                            propertyTypeMap[prop] = typeArg
                        }
                    }
                }
            }
        }

        for ((propName, value) in properties) {
            val prop = allProperties.find { it.cliName == propName }
            if (prop != null) {
                val targetClass = propertyTypeMap[prop]
                val typedValue: Any = when (targetClass) {
                    java.lang.Boolean::class.java, Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> value.toBoolean()
                    java.lang.Integer::class.java, Int::class.javaObjectType, Int::class.javaPrimitiveType -> value.toInt()
                    java.lang.Long::class.java, Long::class.javaObjectType, Long::class.javaPrimitiveType -> value.toLong()
                    java.lang.Double::class.java, Double::class.javaObjectType, Double::class.javaPrimitiveType -> value.toDouble()
                    else -> value
                }
                @Suppress("UNCHECKED_CAST")
                (prop as io.kartographer.core.Property<Any>).set(typedValue)
            } else {
                echo("Error: Unknown property --$propName", err = true)
                throw com.github.ajalt.clikt.core.ProgramResult(1)
            }
        }
        
        val sudoTasks = executionSet.filterIsInstance<Task>().filter { it.requiresElevation }
        // Already root (whole run wrapped in `llm-sudo <karto> ...`): sudo needs
        // no password, and prompting would block non-interactive runs.
        val alreadyRoot = System.getenv("USER") == "root" || System.getProperty("user.name") == "root"
        if (sudoTasks.isNotEmpty() && !alreadyRoot && !System.getProperty("os.name").lowercase().contains("win")) {
            println("================================================================")
            println("🔒 PRIVILEGE ESCALATION REQUIRED")
            println("----------------------------------------------------------------")
            println("The following tasks require Sudo privileges to execute:")
            sudoTasks.forEach { println("  - ${it.name}") }
            println()
            val console = System.console()
            val password = if (console != null) {
                String(console.readPassword("Please enter your sudo password to authorize: ") ?: CharArray(0))
            } else {
                print("Please enter your sudo password to authorize: ")
                java.util.Scanner(System.`in`).nextLine() ?: ""
            }
            context.variables["SUDO_PASSWORD"] = password
            println("================================================================\n")
        }

        // Launch renderer in a separate coroutine
        val renderJob = launch(Dispatchers.Default) {
            try {
                renderer.render()
            } catch (e: Exception) {
                System.err.println("Renderer crashed: ${e.message}")
                e.printStackTrace()
            }
        }

        val result = scheduler.execute(
            targetNode = targetNode!!,
            context = context,
            checkTopology = { checkTopology(it, worker) }
        )

        // Let renderer finish processing final events
        renderer.events.close()
        renderJob.join()

        when (result) {
            is ExecutionResult.Success, is ExecutionResult.UpToDate -> {
                // Handled by renderer output
            }
            is ExecutionResult.Failure -> {
                throw com.github.ajalt.clikt.core.ProgramResult(result.exitCode ?: 1)
            }
        }
    }

    private fun getTransitiveDependencies(node: io.kartographer.core.model.Node): Set<io.kartographer.core.model.Node> {
        val deps = mutableSetOf<io.kartographer.core.model.Node>()
        fun collect(n: io.kartographer.core.model.Node) {
            for (dep in n.dependencyObjects) {
                if (deps.add(dep)) {
                    collect(dep)
                }
            }
            val allNodes = TaskRegistry.nodes
            for (depName in n.dependencyNames) {
                val depNode = allNodes[TaskId(depName)]
                if (depNode != null && deps.add(depNode)) {
                    collect(depNode)
                }
            }
        }
        collect(node)
        return deps
    }

    private fun checkTopology(node: io.kartographer.core.model.Node, worker: Worker): Boolean {
        if (node !is Task) return true
        for (req in node.topologyRequirements) {
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
    val completeEnv = System.getenv().keys.firstOrNull { it.endsWith("_COMPLETE") }
    if (completeEnv != null) {
        CustomAutocomplete.handle(completeEnv, args)
    }

    // Rewrite args to support direct task properties like --version
    val newArgs = mutableListOf<String>()
    var i = 0
    val knownOptions = setOf("--ui", "-d", "--project-dir", "-p", "--pipeline", "--help", "-h")
    val hasRun = args.contains("run")
    
    // Check if a task name is present for run
    val taskArg = if (hasRun) args.find { !it.startsWith("-") && it != "run" && it != "kartographer" } else null

    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--") && arg !in knownOptions && hasRun) {
            val propName = arg.substring(2)
            newArgs.add("-P")
            if (propName.contains('=')) {
                // --name=value form: already a complete assignment.
                newArgs.add(propName)
            } else if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                newArgs.add("$propName=${args[++i]}")
            } else {
                newArgs.add("$propName=true")
            }
        } else if ((arg == "--help" || arg == "-h") && hasRun && taskArg != null) {
            newArgs.add("--task-help")
        } else {
            newArgs.add(arg)
        }
        i++
    }

    KartographerCommand()
        .subcommands(TasksCommand(), GraphCommand(), RunCommand())
        .main(newArgs)
}
