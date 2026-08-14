package io.kartographer.daemon.project

import io.kartographer.core.model.Task
import io.kartographer.core.model.TaskId
import org.slf4j.LoggerFactory

/**
 * Answers shell tab-completion against an already-loaded pipeline.
 *
 * This is the logic that used to run in the CLI, where every TAB press paid for a JVM start
 * and a pipeline load (~1.2s measured). Here the pipeline is already resident, so the cost is
 * the fingerprint check plus whatever a dynamic provider does.
 */
object CompletionResolver {
    private val logger = LoggerFactory.getLogger(CompletionResolver::class.java)

    /**
     * @param words the shell's COMP_WORDS
     * @param cword the shell's COMP_CWORD — the index of the word being completed
     */
    suspend fun complete(project: LoadedProject, words: List<String>, cword: Int): List<String> {
        val currentToken = words.getOrNull(cword) ?: ""
        val previousToken = words.getOrNull(cword - 1) ?: ""

        val runIndex = words.indexOf("run")
        if (runIndex == -1) {
            return completeTaskNames(project, currentToken)
        }

        val taskName = words.getOrNull(runIndex + 1)?.takeUnless { it.startsWith("-") }
        // Still typing the task name itself.
        if (taskName == null || cword == runIndex + 1) {
            return completeTaskNames(project, currentToken)
        }

        val target = project.nodes[TaskId(taskName)] ?: return emptyList()
        val properties = Nodes.closure(target, project.nodes)
            .filterIsInstance<Task>()
            .flatMap { it.registeredProperties }
            .filter { it.cliName != null }

        // Completing the value of `--flag <TAB>`.
        properties.find { "--${it.cliName}" == previousToken }?.let { property ->
            val provider = property.completionProvider ?: return emptyList()
            return try {
                provider.invoke().filter { it.startsWith(currentToken) }
            } catch (e: Exception) {
                // A user-supplied provider throwing must not break the shell; offering nothing
                // is the only sane fallback.
                logger.warn("Completion provider for --{} failed: {}", property.cliName, e.localizedMessage)
                emptyList()
            }
        }

        if (currentToken.startsWith("--")) {
            return properties.map { "--${it.cliName}" }.filter { it.startsWith(currentToken) }.distinct()
        }

        return emptyList()
    }

    private fun completeTaskNames(project: LoadedProject, prefix: String): List<String> =
        project.nodes.keys
            .map { it.value }
            .filter { it.startsWith(prefix) }
            .sorted()
}
