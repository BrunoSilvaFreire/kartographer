package io.kartographer.cli

import io.kartographer.core.model.Node
import io.kartographer.core.model.TaskId
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.scripting.KartographerScript
import io.kartographer.core.plugins.PluginLoader
import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
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

        val scriptingHost = BasicJvmScriptingHost(cachingHostConfiguration())

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

    /**
     * Caches compiled scripts as jars so a repeated `karto` invocation skips the Kotlin
     * compiler entirely (~2.2s of a ~2.5s cold run).
     *
     * The cache key covers the script text plus the identity (path/size/mtime) of every
     * classpath and plugin jar, so upgrading the CLI or rebuilding a local plugin misses
     * the cache. To wipe it by hand: rm -rf ~/.kartographer/script-cache
     */
    private fun cachingHostConfiguration(): ScriptingHostConfiguration {
        val cacheDir = File(System.getProperty("user.home") ?: ".", ".kartographer/script-cache")
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            // ponytail: unwritable cache dir just means no caching, not a failed run
            return defaultJvmScriptingHostConfiguration
        }
        return ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm {
                compilationCache(CompiledScriptJarsCache { script, _ ->
                    File(cacheDir, "karto-${cacheKey(script.text)}.jar")
                })
            }
        }
    }

    private fun cacheKey(scriptText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(scriptText.toByteArray())
        val classpath = (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
        for (entry in classpath + PluginLoader.getPluginJars().map { it.absolutePath }) {
            val file = File(entry)
            digest.update("$entry:${file.length()}:${file.lastModified()}".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
