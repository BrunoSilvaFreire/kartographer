package io.kartographer.cli

import io.kartographer.core.Property
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import io.kartographer.dsl.TaskRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

object CustomAutocomplete {
    fun loadPipelineEarly(projectDir: String?, pipelineClass: String?) {
        val dir = projectDir?.let { File(it) } ?: File(".")
        
        val pClass = pipelineClass ?: run {
            val defaultScript = File(dir, "karto.kts")
            if (defaultScript.exists()) {
                defaultScript.absolutePath
            } else {
                return
            }
        }

        try {
            if (pClass.endsWith(".kts")) {
                KtsPipelineLoader().load(pClass)
            } else {
                val clazz = Class.forName(pClass)
                clazz.getDeclaredConstructor().newInstance()
            }
        } catch (e: Exception) {
            // Ignore errors during autocomplete pipeline loading
        }
    }

    fun handle(envKey: String, args: Array<String>) {
        // Clikt complete env vars are like _KARTOGRAPHER_COMPLETE=bash
        val shell = System.getenv(envKey) ?: return
        
        // Extract projectDir and pipelineClass from args to bootstrap
        var projectDir: String? = null
        var pipelineClass: String? = null
        
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-d", "--project-dir" -> if (i + 1 < args.size) projectDir = args[++i]
                "-p", "--pipeline" -> if (i + 1 < args.size) pipelineClass = args[++i]
            }
            i++
        }
        
        // Find if we are completing the `run` command
        val runIndex = args.indexOf("run")
        if (runIndex == -1 || runIndex >= args.size - 1) {
            return // Let Clikt handle basic completion
        }
        
        val taskName = args[runIndex + 1]
        if (taskName.startsWith("-")) {
            return // Not a valid task name
        }
        
        // We have a task! Load pipeline
        loadPipelineEarly(projectDir, pipelineClass)
        
        val targetNode = TaskRegistry.nodes[TaskId(taskName)] ?: return
        val allNodes = mutableSetOf<Node>()
        
        fun collect(n: Node) {
            if (allNodes.add(n)) {
                n.dependencyObjects.forEach { collect(it) }
                n.dependencyNames.forEach { depName ->
                    TaskRegistry.nodes[TaskId(depName)]?.let { collect(it) }
                }
            }
        }
        collect(targetNode)
        
        val properties = allNodes.filterIsInstance<Task>().flatMap { it.registeredProperties }.filter { it.cliName != null }
        
        // Find the token being completed. Clikt usually passes the whole args string, but we can look at the last arg
        val compWords = System.getenv("COMP_WORDS")?.split(Regex("\\s+")) ?: args.toList()
        val compCword = System.getenv("COMP_CWORD")?.toIntOrNull() ?: (compWords.size - 1)
        
        val currentToken = if (compCword >= 0 && compCword < compWords.size) compWords[compCword] else ""
        val prevToken = if (compCword - 1 >= 0 && compCword - 1 < compWords.size) compWords[compCword - 1] else ""
        
        // Check if we are completing a value for an existing property
        val matchingProp = properties.find { "--${it.cliName}" == prevToken }
        if (matchingProp != null && matchingProp.completionProvider != null) {
            runBlocking {
                val suggestions = matchingProp.completionProvider!!.invoke()
                // Simple output for now, depends on shell, but usually newline separated
                suggestions.filter { it.startsWith(currentToken) }.forEach { println(it) }
            }
            exitProcess(0)
        }
        
        // If we are completing a flag
        if (currentToken.startsWith("--")) {
            properties.forEach { prop ->
                val flag = "--${prop.cliName}"
                if (flag.startsWith(currentToken)) {
                    println(flag)
                }
            }
            exitProcess(0)
        }
        
        // Let Clikt fallback if nothing matches
    }
}
