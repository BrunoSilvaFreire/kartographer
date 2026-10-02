package io.kartographer.core.model

import kotlinx.coroutines.sync.Semaphore

/**
 * A named resource that only so many tasks may hold at once.
 *
 * Declared by the pipeline (`val assets = semaphore("flutter-assets")`) or by a task type
 * that knows its own conflicts, and named on a task with [Task.acquires]. Two tasks naming
 * the same string contend, wherever in the tree they live — the name is the resource.
 *
 * This is not the same knob as the run's job cap. The cap says how much work the machine
 * can take; a semaphore says two particular pieces of work cannot overlap, however idle
 * the machine is.
 */
data class SemaphoreHandle(val name: String, val permits: Int = 1) {
    init {
        require(name.isNotBlank()) { "A semaphore needs a name." }
        require(permits >= 1) { "Semaphore '$name' needs at least one permit, got $permits." }
    }
}

/**
 * The semaphores of one run, one [Semaphore] per declared name.
 *
 * Built from the tasks in the execution plan rather than from a global registry: a name
 * nobody acquires is not a resource, and two pipelines loaded into the same daemon must
 * not share permits.
 */
class SemaphoreRegistry(declarations: Collection<SemaphoreHandle>) {
    private val semaphores: Map<String, Semaphore>

    init {
        val permits = LinkedHashMap<String, Int>()
        for (handle in declarations) {
            val existing = permits.put(handle.name, handle.permits)
            require(existing == null || existing == handle.permits) {
                "Semaphore '${handle.name}' is declared with both $existing and ${handle.permits} permits."
            }
        }
        semaphores = permits.mapValues { (_, count) -> Semaphore(count) }
    }

    val names: Set<String> get() = semaphores.keys

    internal fun semaphoreFor(name: String): Semaphore? = semaphores[name]

    /**
     * Takes every permit in [handles] and returns the lease holding them.
     *
     * Acquired in name order so two tasks wanting the same pair cannot each hold half of
     * it. [onWait] fires only when a permit is not immediately available, so a caller can
     * say what it is waiting for without narrating the uncontended case.
     */
    suspend fun acquire(
        handles: Collection<SemaphoreHandle>,
        onWait: (String) -> Unit = {}
    ): SemaphoreLeases {
        val held = linkedSetOf<String>()
        val lease = SemaphoreLeases(this, held)
        try {
            for (name in handles.map { it.name }.distinct().sorted()) {
                val semaphore = semaphoreFor(name) ?: continue
                if (!semaphore.tryAcquire()) {
                    onWait(name)
                    semaphore.acquire()
                }
                lease.record(name)
            }
        } catch (error: Throwable) {
            // Cancelled part-way through: hand back what we already took, or the permits
            // are gone for the rest of the run.
            lease.releaseAll()
            throw error
        }
        return lease
    }
}

/**
 * The permits one running task holds.
 *
 * A task may hand a permit back before it finishes — see [release]. Every method is safe
 * to call from a process-output callback, which is where the interesting release happens
 * and which does not run on the task's coroutine.
 */
class SemaphoreLeases internal constructor(
    private val registry: SemaphoreRegistry,
    private val held: MutableSet<String>
) {
    internal fun record(name: String) {
        synchronized(held) { held.add(name) }
    }

    /** The names still held, for a task that wants to check before doing conflicting work. */
    val holding: Set<String> get() = synchronized(held) { held.toSet() }

    /**
     * Gives up one permit early, so the next task in line can start while this one keeps
     * running. Idempotent: releasing twice, or releasing something never held, does
     * nothing — which is what lets the scheduler release everything at the end without
     * having to know what the task already did.
     */
    fun release(handle: SemaphoreHandle) = release(handle.name)

    fun release(name: String) {
        val wasHeld = synchronized(held) { held.remove(name) }
        if (wasHeld) registry.semaphoreFor(name)?.release()
    }

    fun releaseAll() {
        val names = synchronized(held) { held.toList().also { held.clear() } }
        names.forEach { registry.semaphoreFor(it)?.release() }
    }
}
