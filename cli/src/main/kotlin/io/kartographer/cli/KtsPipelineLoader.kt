package io.kartographer.cli

import io.kartographer.core.model.Node
import io.kartographer.core.model.TaskId
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.scripting.KartographerScript
import io.kartographer.core.plugins.PluginLoader
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.baseClassLoader

class KtsPipelineLoader {
    fun load(sourcePath: String): Map<TaskId, Node> {
        val scriptFile = File(sourcePath).absoluteFile
        if (!scriptFile.exists()) {
            throw IllegalArgumentException("Script file not found: $sourcePath")
        }

        val scriptDir = scriptFile.parentFile
        val localPluginDir = File(scriptDir, "karto")
        if (localPluginDir.exists() && localPluginDir.isDirectory && File(localPluginDir, "build.gradle.kts").exists()) {
            val gradlewFile = PluginLoader.findGradlew(scriptDir)
            if (gradlewFile != null) {
                println("Compiling local plugin: ${localPluginDir.absolutePath}")
                if (PluginLoader.compileLocalPlugin(localPluginDir, gradlewFile)) {
                    val jarFile = PluginLoader.findLocalPluginJar(localPluginDir)
                    if (jarFile != null) {
                        println("Loading compiled local plugin jar: ${jarFile.absolutePath}")
                        PluginLoader.addPluginJar(jarFile)
                    } else {
                        System.err.println("Local plugin compiled successfully but jar was not found in build/libs")
                    }
                }
            } else {
                System.err.println("Could not locate gradlew wrapper to compile local plugin")
            }
        }

        // Clear existing tasks
        TaskRegistry.clear()

        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<KartographerScript> {
            jvm {
                dependenciesFromCurrentContext(wholeClasspath = true)
                val jars = PluginLoader.getPluginJars()
                if (jars.isNotEmpty()) {
                    updateClasspath(jars)
                }
            }
        }
        
        val evaluationConfiguration = ScriptEvaluationConfiguration {
            jvm {
                baseClassLoader(PluginLoader.getClassLoader())
            }
        }

        val scriptingHost = BasicJvmScriptingHost()

        val result = scriptingHost.eval(scriptFile.toScriptSource(), compilationConfiguration, evaluationConfiguration)

        when (result) {
            is ResultWithDiagnostics.Success<EvaluationResult> -> {
                // TaskRegistry is now populated
                return TaskRegistry.nodes
            }
            is ResultWithDiagnostics.Failure -> {
                val errors = result.reports.joinToString("\n") { it.render() }
                throw RuntimeException("Failed to compile or execute script:\n$errors")
            }
        }
    }
}
