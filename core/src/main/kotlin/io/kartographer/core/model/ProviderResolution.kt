package io.kartographer.core.model

import io.kartographer.core.Provider
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The ambient state a provider needs while it resolves: the execution context to
 * run subprocesses through, the outputs tasks have produced so far, and a cache.
 *
 * A coroutine context element rather than a `ThreadLocal`, because everything
 * that reads it can now suspend — a `ThreadLocal` set around a suspending call
 * is lost the moment that call resumes on another dispatcher thread.
 */
class ProviderResolution(
    val context: ExecutionContext,
    /** Outputs by invocation id, for [Output] to read a dependency's result. */
    val producedOutputs: Map<String, Map<String, Any?>>,
    private val cache: ConcurrentHashMap<Provider<*>, CompletableDeferred<Any?>> = ConcurrentHashMap(),
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ProviderResolution>

    /** Same context, fresh view of what has been produced. */
    fun withProducedOutputs(outputs: Map<String, Map<String, Any?>>): ProviderResolution =
        ProviderResolution(context, outputs, cache)

    /**
     * Resolves [provider] at most once per run.
     *
     * Two tasks reading the same secret must not shell out twice, and the cache
     * belongs to the run rather than to the provider so the daemon's long-lived
     * project instances cannot serve a stale value into the next run.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> resolve(provider: Provider<T>, compute: suspend () -> T): T {
        val pending = CompletableDeferred<Any?>()
        val existing = cache.putIfAbsent(provider, pending)
        if (existing != null) return existing.await() as T
        return try {
            compute().also { pending.complete(it) }
        } catch (error: Throwable) {
            // Leave nothing cached: a failure that was transient (an expired
            // kubeconfig, say) should be retryable within the same run.
            cache.remove(provider, pending)
            pending.completeExceptionally(error)
            throw error
        }
    }
}

/**
 * The resolution scope for the running task.
 *
 * Absent means someone called [Provider.get] outside a run — from a pipeline
 * script's top level, for instance, where there is no execution environment to
 * run `kubectl` through.
 */
suspend fun currentProviderResolution(): ProviderResolution =
    coroutineContext[ProviderResolution] ?: throw IllegalStateException(
        "No provider resolution scope is active. Providers resolve while a task " +
            "builds its inputs or executes; a get() outside a run has no execution " +
            "environment to resolve against."
    )

/** The resolution scope, or null when there is none. */
suspend fun providerResolutionOrNull(): ProviderResolution? = coroutineContext[ProviderResolution]
