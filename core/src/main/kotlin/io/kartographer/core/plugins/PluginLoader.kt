package io.kartographer.core.plugins

import io.kartographer.core.model.Task
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * One plugin classpath: the jars in [pluginsDir] plus any jar handed to [addPluginJar],
 * loaded through a [URLClassLoader] of its own.
 *
 * A long-lived daemon serves several projects at once, and a locally compiled `karto/` jar
 * must only be visible to the project that built it — so this is a class, not a singleton.
 * Project sets are created with the global set's loader as [parentClassLoader], which makes
 * globally installed plugins visible to every project while keeping local jars private.
 */
class PluginSet(
    private val pluginsDir: File?,
    private val parentClassLoader: ClassLoader
) : Closeable {
    companion object {
        private val logger = LoggerFactory.getLogger(PluginSet::class.java)
    }

    private var classLoader: URLClassLoader? = null
    private val loadedModules = mutableListOf<KartographerModule>()
    private val taskTypeMap = mutableMapOf<String, Class<out Task>>()
    private val dynamicJars = mutableListOf<File>()

    init {
        reload()
    }

    /** Rebuilds the classloader from [pluginsDir] alone, discarding any dynamic jars. */
    @Synchronized
    fun reload() {
        val jarFiles = directoryJars()
        rebuild(jarFiles.map { it.toURI().toURL() })
        dynamicJars.clear()
    }

    /**
     * Adds a freshly compiled jar to the front of the classpath so it shadows any stale copy
     * of the same plugin sitting in the plugins directory.
     */
    @Synchronized
    fun addPluginJar(jarFile: File) {
        if (!dynamicJars.contains(jarFile)) {
            dynamicJars.add(jarFile)
        }

        val jarUrl = jarFile.toURI().toURL()
        val existingUrls = (classLoader?.urLs ?: emptyArray()).filter { it != jarUrl }

        existingUrls.map { File(it.path).name }.filter { it == jarFile.name }.forEach {
            logger.warn(
                "Plugin jar '{}' also exists in the global plugins directory; the locally compiled version takes precedence.",
                it
            )
        }

        rebuild(listOf(jarUrl) + existingUrls)
    }

    /**
     * Swaps in a new classloader and re-runs discovery against it. Modules registered before
     * this point may hold classes from the loader we are about to close, so everything is
     * re-registered rather than merged.
     */
    private fun rebuild(urls: List<java.net.URL>) {
        try {
            classLoader?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close old classLoader: {}", e.localizedMessage)
        }

        classLoader = if (urls.isEmpty()) null else URLClassLoader(urls.toTypedArray(), parentClassLoader)

        loadedModules.clear()
        taskTypeMap.clear()

        val activeLoader = classLoader ?: parentClassLoader
        try {
            // ServiceLoader walks the whole delegation chain, so a project set also picks up
            // the modules its parent (the global set) provides.
            for (module in ServiceLoader.load(KartographerModule::class.java, activeLoader)) {
                if (loadedModules.none { it.id == module.id }) {
                    loadedModules.add(module)
                }
                for (type in module.taskTypes) {
                    taskTypeMap[type.name] = type
                    taskTypeMap[type.simpleName] = type
                }
            }
        } catch (e: Exception) {
            logger.warn("Error loading Kartographer modules via ServiceLoader: {}", e.localizedMessage, e)
        }
    }

    private fun directoryJars(): List<File> {
        val dir = pluginsDir ?: return emptyList()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { _, name -> name.endsWith(".jar") }?.toList() ?: emptyList()
    }

    fun getClassLoader(): ClassLoader = classLoader ?: parentClassLoader

    fun getLoadedModules(): List<KartographerModule> = loadedModules.toList()

    /** Dynamic (locally compiled) jars first — same precedence as the classloader. */
    @Synchronized
    fun getPluginJars(): List<File> = (dynamicJars + directoryJars()).distinct()

    fun findTaskClass(className: String): Class<out Task>? {
        taskTypeMap[className]?.let { return it }

        return try {
            @Suppress("UNCHECKED_CAST")
            Class.forName(className, true, getClassLoader()) as Class<out Task>
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    /**
     * Releases this set's classloader. Only call once no task instance loaded through it is
     * still in use — closing a loader breaks classes that have not been fully resolved yet.
     */
    @Synchronized
    override fun close() {
        try {
            classLoader?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close classLoader: {}", e.localizedMessage)
        }
        classLoader = null
        loadedModules.clear()
        taskTypeMap.clear()
        dynamicJars.clear()
    }
}

/**
 * The process-wide plugin set, backed by `$KARTOGRAPHER_PLUGINS_DIR` (default
 * `~/.kartographer/plugins`), plus the stateless helpers for finding and building a
 * project's local `karto/` plugin.
 */
object PluginLoader {
    private var globalSet: PluginSet = PluginSet(getPluginsDirectory(), defaultParentClassLoader())

    /** Rebuilds the global set, optionally pointing it at a different plugins directory. */
    @Synchronized
    fun initialize(pluginsDir: File? = null) {
        globalSet.close()
        globalSet = PluginSet(pluginsDir ?: getPluginsDirectory(), defaultParentClassLoader())
    }

    /**
     * A plugin set private to one project, layered over the global one so global plugins stay
     * visible. It scans no directory of its own — local jars arrive via [PluginSet.addPluginJar].
     */
    fun createProjectSet(): PluginSet = PluginSet(null, globalSet.getClassLoader())

    @Synchronized
    fun addPluginJar(jarFile: File) = globalSet.addPluginJar(jarFile)

    fun getClassLoader(): ClassLoader = globalSet.getClassLoader()

    fun getLoadedModules(): List<KartographerModule> = globalSet.getLoadedModules()

    fun getPluginJars(): List<File> = globalSet.getPluginJars()

    fun findTaskClass(className: String): Class<out Task>? = globalSet.findTaskClass(className)

    private fun defaultParentClassLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader ?: this::class.java.classLoader

    private fun getPluginsDirectory(): File {
        val envPath = System.getenv("KARTOGRAPHER_PLUGINS_DIR")
        if (!envPath.isNullOrEmpty()) {
            return File(envPath)
        }
        val userHome = System.getProperty("user.home") ?: "."
        return File(userHome, ".kartographer/plugins")
    }

    fun findGradlew(startDir: File): File? {
        var dir: File? = startDir.absoluteFile
        while (dir != null) {
            val gradlew = File(dir, "gradlew")
            if (gradlew.exists() && gradlew.isFile) {
                return gradlew
            }
            dir = dir.parentFile
        }
        return null
    }

    fun compileLocalPlugin(localPluginDir: File, gradlewFile: File): Boolean {
        try {
            val pb = ProcessBuilder(
                gradlewFile.absolutePath,
                "-p", localPluginDir.absolutePath,
                "jar"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                System.err.println("Failed to compile local plugin inside $localPluginDir:\n$output")
                return false
            }
            return true
        } catch (e: Exception) {
            System.err.println("Exception compiling local plugin: ${e.localizedMessage}")
            e.printStackTrace()
            return false
        }
    }

    fun findLocalPluginJar(localPluginDir: File): File? {
        val libsDir = File(localPluginDir, "build/libs")
        if (libsDir.exists() && libsDir.isDirectory) {
            val jars = libsDir.listFiles { _, name ->
                name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
            }
            return jars?.firstOrNull()
        }
        return null
    }
}
