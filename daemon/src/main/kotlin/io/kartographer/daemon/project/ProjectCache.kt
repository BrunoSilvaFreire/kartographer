package io.kartographer.daemon.project

import io.kartographer.core.model.Node
import io.kartographer.core.model.TaskId
import io.kartographer.core.plugins.PluginLoader
import io.kartographer.core.plugins.PluginSet
import io.kartographer.dsl.NodeRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One pipeline, compiled and resident in memory, with the plugin classpath it was loaded
 * through.
 *
 * Callers hold it via [ProjectCache.use] rather than keeping a reference: when a reload
 * replaces this entry its classloader must stay open until every in-flight run is done with
 * the task instances it produced.
 */
class LoadedProject internal constructor(
    val projectDir: File,
    val pipelinePath: String,
    val registry: NodeRegistry,
    val pluginSet: PluginSet,
    internal val fingerprint: ProjectFingerprint
) {
    private val users = AtomicInteger(0)
    private val retired = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    val nodes: Map<TaskId, Node>
        get() = registry.nodes

    /** The `project { rootDirectory = ... }` value this pipeline declared, if any. */
    val declaredProjectRoot: String?
        get() = registry.projectRoot

    internal fun retain() {
        users.incrementAndGet()
    }

    internal fun release() {
        if (users.decrementAndGet() <= 0 && retired.get()) dispose()
    }

    /** Marks this version superseded; it is disposed once the last user releases it. */
    internal fun retire() {
        retired.set(true)
        if (users.get() <= 0) dispose()
    }

    private fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            pluginSet.close()
        }
    }
}

/**
 * Keeps compiled pipelines warm across CLI invocations, which is the entire point of the
 * daemon: a repeat `karto tasks` costs a fingerprint check instead of a Kotlin compile.
 *
 * Validation is stat-based and happens on every request, so the cache is correct by
 * construction. A `WatchService` could later pre-warm entries in the background without
 * changing this contract — the fingerprint stays the authority.
 */
class ProjectCache(private val loader: KtsPipelineLoader = KtsPipelineLoader()) {
    companion object {
        private val logger = LoggerFactory.getLogger(ProjectCache::class.java)
    }

    private data class Key(val projectDir: String, val pipeline: String)

    private class Slot {
        val mutex = Mutex()
        var project: LoadedProject? = null
    }

    private val slots = ConcurrentHashMap<Key, Slot>()

    /** How many pipelines are currently resident, for `GetDaemonInfo`. */
    val size: Int
        get() = slots.values.count { it.project != null }

    /**
     * Runs [block] against the loaded pipeline for [projectDir], compiling or recompiling it
     * first if anything it was built from has changed.
     *
     * [pipelineSpec] is the `--pipeline` override — a `.kts` path or a compiled class name.
     * When null, `<projectDir>/karto.kts` is used.
     */
    suspend fun <R> use(projectDir: File, pipelineSpec: String?, block: suspend (LoadedProject) -> R): R {
        val project = acquire(projectDir, pipelineSpec)
        return try {
            block(project)
        } finally {
            project.release()
        }
    }

    private suspend fun acquire(projectDir: File, pipelineSpec: String?): LoadedProject {
        val canonicalDir = projectDir.canonicalFile
        val pipeline = resolvePipeline(canonicalDir, pipelineSpec)
        val key = Key(canonicalDir.path, pipeline)
        val slot = slots.computeIfAbsent(key) { Slot() }

        // One compile per project at a time: without this, two shells tab-completing the same
        // project at once would both run gradlew and race on the plugin jar.
        return slot.mutex.withLock {
            val pipelineFile = File(pipeline)
            val cached = slot.project

            if (cached != null && cached.fingerprint == currentFingerprint(
                    pipelineFile, canonicalDir, cached.pluginSet, cached.registry.pipelineInputFiles
                )) {
                cached.retain()
                return@withLock cached
            }

            if (cached != null) {
                logger.info("Project {} changed on disk; reloading pipeline", canonicalDir.path)
            }

            val loaded = load(canonicalDir, pipeline, pipelineFile)
            // Swap wholesale, then retire the old version — never mutate one a run still holds.
            slot.project = loaded
            cached?.retire()
            loaded.retain()
            loaded
        }
    }

    private fun load(projectDir: File, pipeline: String, pipelineFile: File): LoadedProject {
        val registry = NodeRegistry()
        val pluginSet = PluginLoader.createProjectSet()

        try {
            loader.load(pipeline, registry, pluginSet)
        } catch (t: Throwable) {
            pluginSet.close()
            throw t
        }

        // Taken after loading: compiling the local plugin rewrites its jar, so a signature
        // captured beforehand would look stale on the very next request.
        val fingerprint = currentFingerprint(pipelineFile, projectDir, pluginSet, registry.pipelineInputFiles)
        return LoadedProject(projectDir, pipeline, registry, pluginSet, fingerprint)
    }

    // The global jars are folded in explicitly: a project set scans no directory of its own,
    // so installing or upgrading a global plugin would otherwise leave every project cached
    // against the old one.
    private fun currentFingerprint(
        pipelineFile: File,
        projectDir: File,
        pluginSet: PluginSet,
        pipelineInputs: Collection<String> = emptyList()
    ) =
        ProjectFingerprint.of(
            pipelineFile,
            projectDir,
            (pluginSet.getPluginJars() + PluginLoader.getPluginJars()).distinct(),
            pipelineInputs
        )

    private fun resolvePipeline(projectDir: File, pipelineSpec: String?): String {
        if (pipelineSpec != null) {
            // A .kts override is a path; anything else is a class name and stays as-is.
            return if (pipelineSpec.endsWith(".kts")) {
                File(pipelineSpec).let { if (it.isAbsolute) it else File(projectDir, pipelineSpec) }.canonicalPath
            } else {
                pipelineSpec
            }
        }

        val defaultScript = File(projectDir, "karto.kts")
        if (!defaultScript.exists()) {
            throw IllegalArgumentException(
                "Default pipeline script 'karto.kts' not found in ${projectDir.path}. " +
                    "Specify a compiled pipeline class or .kts script with -p/--pipeline."
            )
        }
        return defaultScript.canonicalPath
    }

    /** Drops every cached pipeline, for daemon shutdown. */
    fun clear() {
        slots.values.forEach { slot -> slot.project?.retire() }
        slots.clear()
    }
}
