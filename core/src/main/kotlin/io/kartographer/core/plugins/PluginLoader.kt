package io.kartographer.core.plugins

import io.kartographer.core.model.Task
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

object PluginLoader {
    private val logger = LoggerFactory.getLogger(PluginLoader::class.java)

    private var classLoader: URLClassLoader? = null
    private val loadedModules = mutableListOf<KartographerModule>()
    private val taskTypeMap = mutableMapOf<String, Class<out Task>>()
    private var activePluginsDir: File? = null
    private val dynamicJars = mutableListOf<File>()

    init {
        initialize()
    }

    @Synchronized
    fun initialize(pluginsDir: File? = null) {
        loadedModules.clear()
        taskTypeMap.clear()
        dynamicJars.clear()

        try {
            classLoader?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close old classLoader: {}", e.localizedMessage)
        }
        classLoader = null

        val dir = pluginsDir ?: getPluginsDirectory()
        activePluginsDir = dir
        val jarFiles = if (dir.exists() && dir.isDirectory) {
            dir.listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()
        } else {
            emptyArray()
        }

        if (jarFiles.isNotEmpty()) {
            val urls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
            val parentLoader = Thread.currentThread().contextClassLoader ?: this::class.java.classLoader
            classLoader = URLClassLoader(urls, parentLoader)
        }

        val activeLoader = classLoader ?: Thread.currentThread().contextClassLoader ?: this::class.java.classLoader

        try {
            val loader = ServiceLoader.load(KartographerModule::class.java, activeLoader)
            for (module in loader) {
                loadedModules.add(module)
                for (type in module.taskTypes) {
                    taskTypeMap[type.name] = type
                    taskTypeMap[type.simpleName] = type
                }
            }
        } catch (e: Exception) {
            logger.warn("Error loading Kartographer modules via ServiceLoader: {}", e.localizedMessage, e)
        }
    }

    @Synchronized
    fun addPluginJar(jarFile: File) {
        if (!dynamicJars.contains(jarFile)) {
            dynamicJars.add(jarFile)
        }

        val currentLoader = classLoader
        val parentLoader = Thread.currentThread().contextClassLoader ?: this::class.java.classLoader

        // Dynamic jars (freshly compiled local plugins) go first so they shadow
        // any stale copy of the same plugin in the global plugins directory.
        val existingUrls = (currentLoader?.urLs ?: emptyArray()).filter { it != jarFile.toURI().toURL() }
        val urls = (listOf(jarFile.toURI().toURL()) + existingUrls).toTypedArray()

        existingUrls.map { File(it.path).name }.filter { it == jarFile.name }.forEach {
            logger.warn("Plugin jar '{}' also exists in the global plugins directory; the locally compiled version takes precedence.", it)
        }

        try {
            classLoader?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close old classLoader during addPluginJar: {}", e.localizedMessage)
        }
        val newLoader = URLClassLoader(urls, parentLoader)
        classLoader = newLoader

        // Re-register everything from the new loader: modules registered before this
        // call may hold classes from the now-shadowed (and closed) old loader.
        loadedModules.clear()
        taskTypeMap.clear()
        val loader = ServiceLoader.load(KartographerModule::class.java, newLoader)
        for (module in loader) {
            if (loadedModules.none { it.id == module.id }) {
                loadedModules.add(module)
                for (type in module.taskTypes) {
                    taskTypeMap[type.name] = type
                    taskTypeMap[type.simpleName] = type
                }
            }
        }
    }

    private fun getPluginsDirectory(): File {
        val envPath = System.getenv("KARTOGRAPHER_PLUGINS_DIR")
        if (!envPath.isNullOrEmpty()) {
            return File(envPath)
        }
        val userHome = System.getProperty("user.home") ?: "."
        return File(userHome, ".kartographer/plugins")
    }

    fun getClassLoader(): ClassLoader {
        return classLoader ?: Thread.currentThread().contextClassLoader ?: this::class.java.classLoader
    }

    fun getLoadedModules(): List<KartographerModule> = loadedModules

    fun getPluginJars(): List<File> {
        val dir = activePluginsDir ?: getPluginsDirectory()
        val dirJars = if (dir.exists() && dir.isDirectory) {
            dir.listFiles { _, name -> name.endsWith(".jar") }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
        // Dynamic (locally compiled) jars first — same precedence as the classloader.
        return (dynamicJars + dirJars).distinct()
    }

    fun findTaskClass(className: String): Class<out Task>? {
        taskTypeMap[className]?.let { return it }

        return try {
            val loader = getClassLoader()
            @Suppress("UNCHECKED_CAST")
            Class.forName(className, true, loader) as Class<out Task>
        } catch (e: ClassNotFoundException) {
            null
        }
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
