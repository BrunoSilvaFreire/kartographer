package io.kartographer.tasks.github

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEnvironment
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.Worker
import io.kartographer.core.model.WorkerId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

private suspend fun <I : InputSet, O : OutputSet> Task<I, O>.execute(context: ExecutionContext) =
    execute(context, buildInputs(context))

/** Records command lines and answers each with canned stdout/stderr/exit code. */
private class RecordingEnvironment(
    private val onStdout: (String) -> String = { "" },
    private val onStderr: (String) -> String = { "" },
    private val exit: (String) -> Int = { 0 }
) : ExecutionEnvironment {
    val commands = mutableListOf<String>()
    val workingDirs = mutableListOf<String>()

    override suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>,
        requiresElevation: Boolean,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?
    ): Int {
        commands.add(command)
        workingDirs.add(workingDir)
        onStdout(command).lineSequence().filter { it.isNotEmpty() }.forEach { onStdoutLine?.invoke(it) }
        onStderr(command).lineSequence().filter { it.isNotEmpty() }.forEach { onStderrLine?.invoke(it) }
        return exit(command)
    }
}

private fun context(
    projectDir: Path,
    environment: ExecutionEnvironment,
    dryRun: Boolean = false
) = ExecutionContext(
    projectDir = projectDir,
    worker = Worker(WorkerId("test-worker")),
    executionEnvironment = environment,
    dryRun = dryRun
)

class GitHubTaskPoliciesTest {

    @Test
    fun `variable changes include missing and differing values only`() {
        assertEquals(mapOf("B" to "new", "C" to "value"), variableChanges(mapOf("A" to "same", "B" to "old"), mapOf("A" to "same", "B" to "new", "C" to "value")))
        assertEquals(mapOf("A" to "value"), parseVariables("""[{"name":"A","value":"value"}]"""))
    }

    @Test
    fun `parses rulesets from the gh api payload`() {
        val rulesets = parseRulesets("""[{"id":1,"name":"main-status-checks"},{"id":2,"name":"other"}]""")
        assertEquals(listOf(RepoRuleset(1, "main-status-checks"), RepoRuleset(2, "other")), rulesets)
        assertEquals(emptyList<RepoRuleset>(), parseRulesets("not json"))
    }

    @Test
    fun `reads the desired ruleset name`() {
        assertEquals("main-status-checks", parseRulesetName("""{"name":"main-status-checks","target":"branch"}"""))
        assertEquals(null, parseRulesetName("{}"))
    }

    @Test
    fun `existing name updates and a missing name creates`() {
        val existing = listOf(RepoRuleset(7, "main-status-checks"))
        assertEquals(RulesetAction.Update(7), decideRulesetAction(existing, "main-status-checks"))
        assertEquals(RulesetAction.Create, decideRulesetAction(existing, "another-ruleset"))
        assertEquals(RulesetAction.Create, decideRulesetAction(emptyList(), "main-status-checks"))
    }

    @Test
    fun `detects the free-tier message`() {
        assertTrue(isProTierMessage("gh: Upgrade to GitHub Pro to use rulesets"))
        assertFalse(isProTierMessage("[]"))
    }
}

class GitHubRepoSettingsTest {

    private fun projectWithSettings(): Path {
        val dir = Files.createTempDirectory("gh-settings")
        val settingsDir = Files.createDirectories(dir.resolve(".github/settings"))
        Files.writeString(settingsDir.resolve("repo-settings.json"), """{"has_wiki":true}""")
        Files.writeString(settingsDir.resolve("ruleset-main.json"), """{"name":"main-status-checks"}""")
        return dir
    }

    @Test
    fun `creates a ruleset when none exists`() = runBlocking {
        val env = RecordingEnvironment(onStdout = { if (it.endsWith("/rulesets")) "[]" else "" })
        val task = GitHubRepoSettings("gh-settings").apply { repo = "BrunoSilvaFreire/ambush-console" }

        val result = task.execute(context(projectWithSettings(), env))

        assertTrue(result is io.kartographer.core.model.ExecutionResult.Success<*>)
        assertEquals(3, env.commands.size)
        assertTrue(env.commands[0].startsWith("gh api --method PATCH /repos/BrunoSilvaFreire/ambush-console --input "))
        assertEquals("gh api /repos/BrunoSilvaFreire/ambush-console/rulesets", env.commands[1])
        assertTrue(env.commands[2].startsWith("gh api --method POST /repos/BrunoSilvaFreire/ambush-console/rulesets --input "))
    }

    @Test
    fun `updates an existing ruleset by name`() = runBlocking {
        val env = RecordingEnvironment(
            onStdout = { if (it.endsWith("/rulesets")) """[{"id":42,"name":"main-status-checks"}]""" else "" }
        )
        val task = GitHubRepoSettings("gh-settings").apply { repo = "o/r" }

        task.execute(context(projectWithSettings(), env))

        assertTrue(
            env.commands[2].startsWith("gh api --method PUT /repos/o/r/rulesets/42 --input "),
            "expected a PUT to ruleset 42, got ${env.commands[2]}"
        )
    }

    @Test
    fun `free tiers skip the ruleset step instead of failing`() = runBlocking {
        val env = RecordingEnvironment(onStderr = { "Upgrade to GitHub Pro" })
        val task = GitHubRepoSettings("gh-settings").apply { repo = "o/r" }

        val result = task.execute(context(projectWithSettings(), env))

        assertTrue(result is io.kartographer.core.model.ExecutionResult.Success<*>)
        assertEquals(2, env.commands.size)
    }

    @Test
    fun `dry run executes nothing`() = runBlocking {
        val env = RecordingEnvironment()
        val task = GitHubRepoSettings("gh-settings").apply { repo = "o/r" }

        val result = task.execute(context(projectWithSettings(), env, dryRun = true))

        assertTrue(result is io.kartographer.core.model.ExecutionResult.Success<*>)
        assertEquals(emptyList<String>(), env.commands)
    }
}

class GitHubWikiSyncTest {

    private fun projectWithWiki(): Path {
        val dir = Files.createTempDirectory("gh-wiki")
        val wiki = Files.createDirectories(dir.resolve("docs/wiki"))
        Files.writeString(wiki.resolve("Home.md"), "# Home")
        return dir
    }

    @Test
    fun `uninitialised wiki points at the task to rerun`() = runBlocking {
        val env = RecordingEnvironment(exit = { 1 })
        val task = GitHubWikiSync("wiki-sync").apply { repo = "o/r" }

        val result = task.execute(context(projectWithWiki(), env))

        val failure = result as io.kartographer.core.model.ExecutionResult.Failure
        assertTrue(failure.error.message!!.contains("kartographer run wiki-sync"))
        assertEquals(1, env.commands.size)
    }

    @Test
    fun `no diff means no commit or push`() = runBlocking {
        val env = RecordingEnvironment(
            exit = { command ->
                // clone + add succeed; `diff --staged --quiet` returns 0 (no changes).
                0
            }
        )
        val task = GitHubWikiSync("wiki-sync").apply { repo = "o/r" }

        val result = task.execute(context(projectWithWiki(), env))

        assertTrue(result is io.kartographer.core.model.ExecutionResult.Success<*>)
        assertFalse(env.commands.any { it.startsWith("git commit") })
        assertFalse(env.commands.any { it.startsWith("git push") })
    }

    @Test
    fun `dry run executes nothing`() = runBlocking {
        val env = RecordingEnvironment()
        val task = GitHubWikiSync("wiki-sync").apply { repo = "o/r" }

        val result = task.execute(context(projectWithWiki(), env, dryRun = true))

        assertTrue(result is io.kartographer.core.model.ExecutionResult.Success<*>)
        assertEquals(emptyList<String>(), env.commands)
    }
}
