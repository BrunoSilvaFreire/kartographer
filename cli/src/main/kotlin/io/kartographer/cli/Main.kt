package io.kartographer.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.kartographer.cli.render.MosaicRenderer
import io.kartographer.cli.render.PlainRenderer
import io.kartographer.cli.render.Renderer
import io.kartographer.core.model.ExecutionResult
import io.kartographer.daemon.proto.DescribeProjectResponse
import io.kartographer.daemon.proto.NodeInfo
import io.kartographer.daemon.proto.ProjectContext
import io.kartographer.daemon.proto.PromptKind
import io.kartographer.daemon.proto.RunClientMessage
import io.kartographer.daemon.proto.RunServerMessage
import io.kartographer.daemon.proto.describeProjectRequest
import io.kartographer.daemon.proto.projectContext
import io.kartographer.daemon.proto.promptResponse
import io.kartographer.daemon.proto.runCancel
import io.kartographer.daemon.proto.runClientMessage
import io.kartographer.daemon.proto.runStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * What the user asked for, in the form the daemon needs.
 *
 * The CLI resolves paths and reads the environment because it is the process that actually
 * sits in the user's shell; the daemon's own cwd and environment are whatever launched it.
 */
class ClientContext(
    private val projectDirOption: String?,
    private val pipelineOption: String?
) {
    fun toProto(): ProjectContext = projectContext {
        projectDir = Paths.get(projectDirOption ?: "").toAbsolutePath().normalize().toString()
        pipelineOption?.let { pipeline = it }
        environment.putAll(System.getenv())
        // A pipeline's own `project { rootDirectory }` may only redirect execution when the
        // user did not name a directory explicitly.
        projectDirExplicit = projectDirOption != null
    }
}

class KartographerCommand : CliktCommand(name = "kartographer", invokeWithoutSubcommand = false) {
    private val pipelineClass by option("-p", "--pipeline", help = "Compiled pipeline class name or .kts script path to load")
    private val projectDir by option("-d", "--project-dir", help = "Project directory override")

    override fun run() {
        // No pipeline loading here any more: the daemon owns compilation and keeps the result
        // warm between invocations.
        currentContext.obj = ClientContext(projectDir, pipelineClass)
    }
}

/** Shared plumbing: open a connection, run one request, always release it. */
private fun <R> withDaemon(block: (DaemonConnection) -> R): R {
    val connection = try {
        DaemonConnection.connect()
    } catch (e: DaemonConnection.DaemonUnavailable) {
        System.err.println("Error: ${e.message}")
        throw ProgramResult(1)
    }
    return connection.use(block)
}

private fun CliktCommand.describeProject(context: ClientContext): DescribeProjectResponse =
    withDaemon { connection ->
        try {
            val request = describeProjectRequest { this.context = context.toProto() }
            Timing.mark("describeProject: request built")
            connection.describeProject(request).also { Timing.mark("describeProject: response") }
        } catch (e: Exception) {
            echo("Error loading pipeline: ${e.message}", err = true)
            throw ProgramResult(1)
        }
    }

class TasksCommand : CliktCommand(name = "tasks", help = "List all registered nodes in the pipeline") {
    private val clientContext by requireObject<ClientContext>()

    override fun run() {
        val project = describeProject(clientContext)
        if (project.nodesList.isEmpty()) {
            echo("No tasks registered.")
            return
        }

        fun cliArgs(node: NodeInfo): String {
            val flags = node.propertiesList.mapNotNull { it.cliName.takeIf(String::isNotBlank) }
            return if (flags.isEmpty()) "-" else flags.joinToString(", ") { "--$it" }
        }

        val nodes = project.nodesList
        val maxFullNameLen = maxOf(14, nodes.maxOf { it.name.length })
        val maxNameLen = maxOf(9, nodes.maxOf { it.name.substringAfterLast('/').length })
        val maxTypeLen = maxOf(4, nodes.maxOf { it.type.length })
        val maxCliArgsLen = maxOf(8, nodes.maxOf { cliArgs(it).length })

        echo(
            "${"FULL TASK NAME".padEnd(maxFullNameLen)}   ${"TASK NAME".padEnd(maxNameLen)}   " +
                "${"TYPE".padEnd(maxTypeLen)}   ${"CLI ARGS".padEnd(maxCliArgsLen)}   DEPENDENCIES"
        )
        echo("-".repeat(maxFullNameLen + maxNameLen + maxTypeLen + maxCliArgsLen + 23))

        for (node in nodes) {
            val deps = if (node.dependenciesList.isEmpty()) "-" else node.dependenciesList.joinToString(", ")
            echo(
                "${node.name.padEnd(maxFullNameLen)}   ${node.name.substringAfterLast('/').padEnd(maxNameLen)}   " +
                    "${node.type.padEnd(maxTypeLen)}   ${cliArgs(node).padEnd(maxCliArgsLen)}   $deps"
            )
        }
    }
}

class GraphCommand : CliktCommand(name = "graph", help = "Display the task dependency graph") {
    private val clientContext by requireObject<ClientContext>()

    override fun run() {
        val project = describeProject(clientContext)
        if (project.nodesList.isEmpty()) {
            echo("No tasks registered.")
            return
        }
        if (project.graphError.isNotBlank()) {
            echo("Error validating graph: ${project.graphError}", err = true)
            throw ProgramResult(1)
        }

        echo("Task Dependency Graph:")
        for (node in project.graphList) {
            if (node.dependenciesList.isEmpty()) {
                echo("  ${node.name}")
            } else {
                echo("  ${node.name} -> [${node.dependenciesList.joinToString()}]")
            }
        }
    }
}

class RunCommand : CliktCommand(name = "run", help = "Run a task and all its dependencies") {
    private val clientContext by requireObject<ClientContext>()
    private val taskHelp by option("--task-help", hidden = true).flag()
    private val taskName by argument(help = "The name of the task to run").optional()
    private val properties by option("-P").associate()
    private val ui by option("--ui", help = "Renderer to use (auto, tui, plain)").default("auto")

    override fun run() {
        if (taskHelp && taskName == null) {
            echo(getFormattedHelp())
            return
        }
        if (taskName == null) {
            echo(getFormattedHelp(), err = true)
            echo("Error: Missing argument 'taskname'", err = true)
            throw ProgramResult(1)
        }
        if (taskHelp) {
            printTaskHelp()
            return
        }
        execute()
    }

    private fun printTaskHelp() {
        val project = describeProject(clientContext)
        val node = project.nodesList.find { it.name == taskName }
        if (node == null) {
            echo("Error: Node '$taskName' not found.", err = true)
            throw ProgramResult(1)
        }

        echo("Task: ${node.name}")
        if (node.isTask) {
            if (node.propertiesList.isNotEmpty()) {
                echo("Properties:")
                for (property in node.propertiesList) {
                    val flag = property.cliName.takeIf(String::isNotBlank)?.let { "--$it" }
                        ?: "(internal, no CLI flag)"
                    val lines = StringBuilder("  • ${property.name}: ${property.typeName}")
                    lines.append("\n      CLI Flag: $flag")
                    if (property.description.isNotBlank()) {
                        lines.append("\n      Description: ${property.description}")
                    }
                    if (property.literalCompletionsList.isNotEmpty()) {
                        lines.append("\n      Accepted Values: ${property.literalCompletionsList.joinToString(", ")}")
                    } else if (property.hasDynamicCompletions) {
                        lines.append("\n      Accepted Values: (Dynamic)")
                    }
                    echo(lines.toString())
                }
            }
        } else {
            echo("Description: Task group containing ${node.dependenciesCount} direct dependencies.")
        }

        if (node.dependenciesList.isNotEmpty()) {
            echo("Dependencies: ${node.dependenciesList.joinToString(", ")}")
        }
    }

    private fun execute() {
        val connection = try {
            DaemonConnection.connect()
        } catch (e: DaemonConnection.DaemonUnavailable) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(1)
        }

        val renderer: Renderer = when (ui) {
            "tui" -> MosaicRenderer()
            else -> PlainRenderer() // Defaulting to PlainRenderer as MVP TUI for now
        }

        val stream = connection.startRun(
            runClientMessage {
                start = runStart {
                    context = clientContext.toProto()
                    this.taskName = this@RunCommand.taskName!!
                    this.properties.putAll(this@RunCommand.properties)
                }
            }
        )

        // Ctrl-C: tell the daemon to unwind the run rather than leaving orphaned child
        // processes behind when this JVM dies.
        val cancelHook = Thread {
            runCatching { stream.send(runClientMessage { cancel = runCancel { } }) }
            Thread.sleep(200)
        }
        Runtime.getRuntime().addShutdownHook(cancelHook)

        var failureExitCode: Int? = null
        runBlocking {
            val renderJob = launch(Dispatchers.Default) {
                try {
                    renderer.render()
                } catch (e: Exception) {
                    System.err.println("Renderer crashed: ${e.message}")
                }
            }

            try {
                // Reading blocks on the socket, so it gets a thread that is allowed to block
                // while the renderer keeps draining events on another.
                withContext(Dispatchers.IO) {
                    while (true) {
                        val message = stream.receive() ?: break
                        when (message.messageCase) {
                            RunServerMessage.MessageCase.PROMPT_REQUESTED -> {
                                val answer = answerPrompt(message)
                                stream.send(
                                    runClientMessage {
                                        promptResponse = promptResponse {
                                            requestId = message.promptRequested.requestId
                                            value = answer
                                        }
                                    }
                                )
                            }
                            RunServerMessage.MessageCase.ERROR -> {
                                echo("Error: ${message.error.message}", err = true)
                                failureExitCode = message.error.exitCode.takeIf { it != 0 } ?: 1
                                return@withContext
                            }
                            RunServerMessage.MessageCase.PIPELINE_COMPLETED -> {
                                RemoteEvents.toExecutionEvent(message)?.let { renderer.onEvent(it) }
                                val result = RemoteEvents.toResult(
                                    message.pipelineCompleted.status,
                                    message.pipelineCompleted.errorMessage,
                                    message.pipelineCompleted.exitCode
                                )
                                if (result is ExecutionResult.Failure) {
                                    failureExitCode = result.exitCode ?: 1
                                }
                                return@withContext
                            }
                            else -> RemoteEvents.toExecutionEvent(message)?.let { renderer.onEvent(it) }
                        }
                    }
                }
            } finally {
                renderer.events.close()
                renderJob.join()
                runCatching { Runtime.getRuntime().removeShutdownHook(cancelHook) }
                connection.close()
            }
        }

        failureExitCode?.let { throw ProgramResult(it) }
    }

    /** Prompts run on the real terminal here; only the answer crosses back to the daemon. */
    private fun answerPrompt(message: RunServerMessage): String {
        val request = message.promptRequested

        if (request.kind == PromptKind.PROMPT_SUDO_PASSWORD) {
            println("================================================================")
            println("🔒 PRIVILEGE ESCALATION REQUIRED")
            println("----------------------------------------------------------------")
            println("The following tasks require Sudo privileges to execute:")
            request.elevatedTasksList.forEach { println("  - $it") }
            println()
        }

        val console = System.console()
        val answer = when {
            console != null && request.secret ->
                String(console.readPassword(request.message) ?: CharArray(0))
            console != null -> {
                print(request.message)
                console.readLine() ?: ""
            }
            else -> {
                print(request.message)
                System.out.flush()
                readlnOrNull() ?: ""
            }
        }

        if (request.kind == PromptKind.PROMPT_SUDO_PASSWORD) {
            println("================================================================\n")
        }
        return answer
    }
}

fun main(args: Array<String>) {
    Timing.mark("main entered")
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

    Timing.mark("args rewritten")
    KartographerCommand()
        .subcommands(TasksCommand(), GraphCommand(), RunCommand())
        .main(newArgs)
    Timing.mark("command finished")
}
