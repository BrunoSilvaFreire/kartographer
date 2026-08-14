package io.kartographer.cli

import io.kartographer.daemon.proto.completeRequest
import io.kartographer.daemon.proto.projectContext
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Forwards shell tab completion to the daemon.
 *
 * Completion used to load the whole pipeline in this process on every TAB press (~1.2s
 * measured). Now the daemon answers from an already-compiled pipeline and the CLI only pays
 * for its own startup plus one RPC.
 */
object CustomAutocomplete {

    fun handle(envKey: String, args: Array<String>) {
        // Clikt complete env vars look like _KARTOGRAPHER_COMPLETE=bash
        System.getenv(envKey) ?: return

        var projectDir: String? = null
        var pipeline: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-d", "--project-dir" -> if (i + 1 < args.size) projectDir = args[++i]
                "-p", "--pipeline" -> if (i + 1 < args.size) pipeline = args[++i]
            }
            i++
        }

        val words = System.getenv("COMP_WORDS")?.split(Regex("\\s+")) ?: args.toList()
        val cword = System.getenv("COMP_CWORD")?.toIntOrNull() ?: (words.size - 1)

        val candidates = try {
            requestCandidates(projectDir, pipeline, words, cword)
        } catch (e: Exception) {
            // Never let completion print an error or hang: a broken pipeline, a missing
            // daemon or a slow load must all degrade to "no suggestions".
            emptyList()
        }

        candidates.forEach { println(it) }

        // Always exit, even with nothing to suggest. Returning here would fall through to the
        // normal command path, and the words the shell handed us are a half-typed command
        // line — a TAB press that found no candidates would then *run* it.
        exitProcess(0)
    }

    private fun requestCandidates(
        projectDir: String?,
        pipeline: String?,
        words: List<String>,
        cword: Int
    ): List<String> {
        // autoStart = false: launching a JVM from inside a completion hook would freeze the
        // user's shell for seconds. With no daemon running, completion simply offers nothing.
        val connection = DaemonConnection.connect(autoStart = false)
        return connection.use {
            it.complete(
                completeRequest {
                    context = projectContext {
                        this.projectDir = Paths.get(projectDir ?: "").toAbsolutePath().normalize().toString()
                        pipeline?.let { spec -> this.pipeline = spec }
                        environment.putAll(System.getenv())
                        projectDirExplicit = projectDir != null
                    }
                    this.words.addAll(words)
                    this.cword = cword
                }
            ).candidatesList
        }
    }
}
