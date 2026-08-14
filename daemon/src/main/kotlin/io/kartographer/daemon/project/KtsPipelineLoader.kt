package io.kartographer.daemon.project

import io.kartographer.core.model.Node
import io.kartographer.core.model.TaskId
import io.kartographer.core.plugins.PluginLoader
import io.kartographer.core.plugins.PluginSet
import io.kartographer.dsl.NodeRegistry
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.scripting.KartographerScript
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Compiles and evaluates a pipeline into a caller-supplied [NodeRegistry] and [PluginSet].
 *
 * Lives in the daemon rather than the CLI because it pulls in the Kotlin compiler: keeping it
 * here is what lets the CLI ship without `kotlin-compiler-embeddable`.
 */
class KtsPipelineLoader {
    companion object {
        private val logger = LoggerFactory.getLogger(KtsPipelineLoader::class.java)
    }

    /**
     * Loads [sourcePath] into a throwaway registry and plugin set.
     *
     * For callers that just want the nodes and have no cache to manage — tests, mostly. The
     * daemon goes through [ProjectCache] so the compile is reused.
     */
    fun load(sourcePath: String): Map<TaskId, Node> =
        load(sourcePath, NodeRegistry(), PluginLoader.createProjectSet())

    /**
     * Loads [sourcePath] — a `.kts` script or a compiled pipeline class name — registering its
     * nodes into [registry]. A `karto/` directory next to the script is compiled first and its
     * jar added to [pluginSet].
     */
    fun load(sourcePath: String, registry: NodeRegistry, pluginSet: PluginSet): Map<TaskId, Node> {
        registry.clear()

        if (!sourcePath.endsWith(".kts")) {
            return loadCompiledClass(sourcePath, registry, pluginSet)
        }

        val scriptFile = File(sourcePath).absoluteFile
        if (!scriptFile.exists()) {
            throw IllegalArgumentException("Script file not found: $sourcePath")
        }

        compileLocalPluginIfPresent(scriptFile.parentFile, pluginSet)

        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<KartographerScript> {
            jvm {
                dependenciesFromCurrentContext(wholeClasspath = true)
                val jars = pluginSet.getPluginJars()
                if (jars.isNotEmpty()) {
                    updateClasspath(jars)
                }
            }
        }

        val evaluationConfiguration = ScriptEvaluationConfiguration {
            jvm {
                baseClassLoader(pluginSet.getClassLoader())
            }
        }

        val scriptingHost = BasicJvmScriptingHost(cachingHostConfiguration(pluginSet))

        // The script body runs on this thread, so binding the registry around eval is what
        // routes its `task("x") { }` calls into `registry` instead of the process default.
        val result = TaskRegistry.withRegistry(registry) {
            scriptingHost.eval(scriptFile.toScriptSource(), compilationConfiguration, evaluationConfiguration)
        }

        return when (result) {
            is ResultWithDiagnostics.Success<EvaluationResult> -> registry.nodes
            is ResultWithDiagnostics.Failure -> {
                val errors = result.reports.joinToString("\n") { it.render() }
                throw RuntimeException("Failed to compile or execute script:\n$errors")
            }
        }
    }

    private fun loadCompiledClass(
        className: String,
        registry: NodeRegistry,
        pluginSet: PluginSet
    ): Map<TaskId, Node> {
        val clazz = try {
            Class.forName(className, true, pluginSet.getClassLoader())
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException(
                "Pipeline class '$className' not found. Ensure it is compiled and on the daemon's classpath.",
                e
            )
        }
        // Constructing the pipeline is what registers its nodes, so it has to happen inside
        // the binding just like script evaluation does.
        TaskRegistry.withRegistry(registry) {
            clazz.getDeclaredConstructor().newInstance()
        }
        return registry.nodes
    }

    private fun compileLocalPluginIfPresent(scriptDir: File, pluginSet: PluginSet) {
        val localPluginDir = File(scriptDir, "karto")
        if (!localPluginDir.isDirectory || !File(localPluginDir, "build.gradle.kts").exists()) {
            return
        }

        val gradlewFile = PluginLoader.findGradlew(scriptDir)
        if (gradlewFile == null) {
            logger.warn("Could not locate gradlew wrapper to compile local plugin in {}", localPluginDir.absolutePath)
            return
        }

        logger.info("Compiling local plugin: {}", localPluginDir.absolutePath)
        if (!PluginLoader.compileLocalPlugin(localPluginDir, gradlewFile)) {
            return
        }

        val jarFile = PluginLoader.findLocalPluginJar(localPluginDir)
        if (jarFile == null) {
            logger.warn("Local plugin compiled successfully but jar was not found in build/libs")
            return
        }
        logger.info("Loading compiled local plugin jar: {}", jarFile.absolutePath)
        pluginSet.addPluginJar(jarFile)
    }

    /**
     * Caches compiled scripts as jars so a daemon restart skips the Kotlin compiler
     * (~2.2s of a ~2.5s cold load).
     *
     * The cache key covers the script text plus the identity (path/size/mtime) of every
     * classpath and plugin jar, so upgrading the daemon or rebuilding a local plugin misses
     * the cache. To wipe it by hand: rm -rf ~/.kartographer/script-cache
     */
    private fun cachingHostConfiguration(pluginSet: PluginSet): ScriptingHostConfiguration {
        val cacheDir = File(System.getProperty("user.home") ?: ".", ".kartographer/script-cache")
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            // ponytail: unwritable cache dir just means no caching, not a failed load
            return defaultJvmScriptingHostConfiguration
        }
        return ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm {
                compilationCache(CompiledScriptJarsCache { script, _ ->
                    File(cacheDir, "karto-${cacheKey(script.text, pluginSet)}.jar")
                })
            }
        }
    }

    private fun cacheKey(scriptText: String, pluginSet: PluginSet): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(scriptText.toByteArray())
        val classpath = (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
        // Global jars alongside the project's own: a project set scans no directory, so
        // without them a reinstalled global plugin would silently reuse a stale compile.
        val pluginJars = (pluginSet.getPluginJars() + PluginLoader.getPluginJars())
            .distinct()
            .map { it.absolutePath }
        for (entry in classpath + pluginJars) {
            val file = File(entry)
            digest.update("$entry:${file.length()}:${file.lastModified()}".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
