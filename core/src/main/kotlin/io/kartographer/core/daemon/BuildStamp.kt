package io.kartographer.core.daemon

import java.io.File
import java.security.MessageDigest

/**
 * A signature of the jars a Kartographer process is running.
 *
 * The daemon caches compiled pipelines and holds task classes in memory, so a rebuilt
 * installation talking to an already-running daemon would silently execute the old code.
 * Comparing stamps catches that: the daemon reports the stamp of its own classpath, and the
 * CLI computes the stamp of the daemon distribution it would launch. A mismatch means the
 * running daemon is stale and should be restarted.
 *
 * A version string alone cannot do this — every build in development is `0.1.0-SNAPSHOT`.
 */
object BuildStamp {

    /** The stamp of the currently running process's classpath. */
    fun ofRunningProcess(): String {
        val entries = (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }
        return of(entries)
    }

    /** The stamp of an installed distribution's `lib/` directory. */
    fun ofLibDirectory(libDir: File): String? {
        if (!libDir.isDirectory) return null
        val jars = libDir.listFiles { _, name -> name.endsWith(".jar") }?.toList() ?: return null
        if (jars.isEmpty()) return null
        return of(jars)
    }

    private fun of(entries: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Sorted by absolute path so the two sides agree regardless of classpath ordering.
        for (file in entries.sortedBy { it.absolutePath }) {
            digest.update("${file.absolutePath}:${file.length()}:${file.lastModified()}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
