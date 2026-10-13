package io.kartographer.core.plugins

import io.kartographer.core.model.Task
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.jar.JarFile
import java.net.URL
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
    private val taskTypeMap = mutableMapOf<String, Class<out Task<*, *>>>()
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

        classLoader = if (urls.isEmpty()) null else PluginClassLoader(urls.toTypedArray(), parentClassLoader)

        loadedModules.clear()
        taskTypeMap.clear()

        val activeLoader = classLoader ?: parentClassLoader
        try {
            // ServiceLoader walks the whole delegation chain, so a project set also picks up
            // the modules its parent (the global set) provides.
            for (module in ServiceLoader.load(KartographerModule::class.java, activeLoader)) {
                // Prefer the local implementation when the parent exposes the same module.
                if (loadedModules.any { it.id == module.id }) continue
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

    fun findTaskClass(className: String): Class<out Task<*, *>>? {
        taskTypeMap[className]?.let { return it }

        return try {
            @Suppress("UNCHECKED_CAST")
            Class.forName(className, true, getClassLoader()) as Class<out Task<*, *>>
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
 * Loads plugin implementations before consulting the parent loader. The parent still owns
 * Kartographer's API and runtime classes, while a project-local plugin can replace a stale
 * global implementation with the same class names.
 */
private class PluginClassLoader(urls: Array<URL>, private val parentLoader: ClassLoader) :
    URLClassLoader(urls, parentLoader) {
    private val parentFirstPrefixes = listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "kotlin.",
        "kotlinx.",
        "org.slf4j.",
        "io.kartographer.core."
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return@synchronized it }

            if (!parentFirstPrefixes.any(name::startsWith)) {
                try {
                    val local = findClass(name)
                    if (resolve) resolveClass(local)
                    return@synchronized local
                } catch (_: ClassNotFoundException) {
                    // The implementation may be supplied by the parent or runtime classpath.
                }
            }

            super.loadClass(name, resolve)
        }

    override fun getResources(name: String): java.util.Enumeration<URL> {
        val resources = mutableListOf<URL>()
        try {
            resources += findResources(name).asSequence().toList()
        } catch (_: IOException) {
            // Fall through to parent resources; class loading will report missing classes.
        }
        resources += parentLoader.getResources(name).asSequence().toList()
        return java.util.Collections.enumeration(resources)
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

    fun findTaskClass(className: String): Class<out Task<*, *>>? = globalSet.findTaskClass(className)

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

    /**
     * Finds a Gradle to build a project-local plugin with. A pipeline promoted into the repo it
     * builds no longer sits under Kartographer's own checkout, so the walk up from the script
     * often finds no wrapper at all — a Go or Flutter repo has no reason to carry one.
     */
    fun findGradlew(
        startDir: File,
        pinnedGradle: String? = System.getenv("KARTOGRAPHER_GRADLE"),
        path: String? = System.getenv("PATH")
    ): File? {
        if (!pinnedGradle.isNullOrEmpty()) {
            val file = File(pinnedGradle)
            if (file.canExecute()) {
                return file
            }
            System.err.println("KARTOGRAPHER_GRADLE is set to $pinnedGradle but it is not executable; ignoring it")
        }

        var dir: File? = startDir.absoluteFile
        while (dir != null) {
            val gradlew = File(dir, "gradlew")
            if (gradlew.exists() && gradlew.isFile) {
                return gradlew
            }
            dir = dir.parentFile
        }

        // ponytail: last resort is any gradle on PATH; pin a specific one with KARTOGRAPHER_GRADLE
        return path
            ?.split(File.pathSeparator)
            ?.map { File(it, "gradle") }
            ?.firstOrNull { it.canExecute() }
    }

    fun compileLocalPlugin(localPluginDir: File, gradlewFile: File): Boolean {
        val initScript = createPluginResolutionInitScript() ?: run {
            System.err.println("Unable to locate the running Kartographer plugin artifacts")
            return false
        }
        try {
            val pb = ProcessBuilder(
                gradlewFile.absolutePath,
                "-p", localPluginDir.absolutePath,
                "--init-script", initScript.absolutePath,
                "jar"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            initScript.delete()
            if (exitCode != 0) {
                System.err.println("Failed to compile local plugin inside $localPluginDir:\n$output")
                return false
            }
            return true
        } catch (e: Exception) {
            initScript.delete()
            System.err.println("Exception compiling local plugin: ${e.localizedMessage}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Resolve the versionless plugin marker and its implementation from the same Kartographer
     * build the daemon is running. Release launchers expose lib/ as java.class.path; development
     * runs resolve from mavenLocal, where publishToMavenLocal installs the marker metadata.
     */
    internal fun pluginResolutionInitScriptForTest(): File? = createPluginResolutionInitScript()

    private fun createPluginResolutionInitScript(): File? {
        val entries = (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)
        val pluginJar = entries.firstOrNull {
            it.isFile && it.name.startsWith("kartographer-plugin-") && it.name.endsWith(".jar")
        }
        // Only use the core artifact from the launcher's exposed classpath. In a source
        // run that entry is a classes directory; release start scripts expose the packaged jar.
        val coreJar = entries.firstOrNull {
            it.isFile && it.parentFile?.name == "lib" &&
                it.name.startsWith("core-") && it.name.endsWith(".jar")
        }
        // The plugin jar is normally absent: neither the daemon nor the release bundle depends
        // on it. Fall back to core, whose versioned file name still carries the bundle version
        // because the API jar's manifest does not.
        val version = artifactVersion(pluginJar, "kartographer-plugin-")
            ?: artifactVersion(coreJar, "core-")
            ?: "0.1.0-SNAPSHOT"

        val init = Files.createTempFile("kartographer-plugin-resolution-", ".gradle").toFile()
        // The bundle directory is a flat runtime classpath, not a Maven repository. Its
        // artifacts are versioned and the source build publishes matching artifacts locally.
        init.writeText(
            """
            settingsEvaluated { settings ->
                settings.pluginManagement.resolutionStrategy.eachPlugin {
                    if (requested.id.id == "io.kartographer.plugin" && requested.version == null) {
                        useVersion("$version")
                    }
                }
                // Source builds install these publications here. A release bundle exposes a
                // flat lib/ classpath, so its matching Maven publication must be published.
                settings.pluginManagement.repositories {
                    mavenLocal()
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            """.trimIndent()
        )
        return init
    }

    /**
     * Resolves the version of a Kartographer artifact on the running classpath. The manifest
     * attribute is authoritative; a release bundle's `core` jar is unversioned in its manifest,
     * so the version is recovered from the `<artifactId>-<version>.jar` file name instead.
     */
    private fun artifactVersion(jar: File?, artifactPrefix: String): String? {
        if (jar == null) return null
        jarManifestVersion(jar)?.let { return it }
        if (!jar.name.startsWith(artifactPrefix) || !jar.name.endsWith(".jar")) return null
        return jar.name.removePrefix(artifactPrefix).removeSuffix(".jar").ifBlank { null }
    }

    private fun jarManifestVersion(jar: File): String? = try {
        JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
    } catch (_: Exception) {
        null
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
