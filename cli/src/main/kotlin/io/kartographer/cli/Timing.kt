package io.kartographer.cli

/**
 * Startup timing, printed to stderr when `KARTOGRAPHER_TIMING=1`.
 *
 * The whole point of the daemon is that `karto` starts fast, and "fast" is a claim that needs
 * measuring rather than assuming — this is what tells you whether a slow invocation is JVM
 * startup, the connection handshake, or the daemon actually doing work.
 */
object Timing {
    private val enabled = System.getenv("KARTOGRAPHER_TIMING") == "1"
    private val startNanos = System.nanoTime()

    // Cheap enough to leave in: one nanoTime call and a branch when disabled.
    fun mark(label: String) {
        if (!enabled) return
        val millis = (System.nanoTime() - startNanos) / 1_000_000
        System.err.println("[timing] %5d ms  %s".format(millis, label))
    }

    fun <R> measure(label: String, block: () -> R): R {
        if (!enabled) return block()
        val before = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - before) / 1_000_000
        System.err.println("[timing] %5d ms  %s (took %d ms)".format((System.nanoTime() - startNanos) / 1_000_000, label, elapsed))
        return result
    }
}
