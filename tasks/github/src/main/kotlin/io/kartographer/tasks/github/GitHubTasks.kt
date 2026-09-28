package io.kartographer.tasks.github

import io.kartographer.core.model.BashSpec
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

internal val githubJson = Json { ignoreUnknownKeys = true }

/** A branch ruleset as returned by `gh api /repos/:repo/rulesets`. */
internal data class RepoRuleset(val id: Long, val name: String)

/** What to do with the desired ruleset once the existing set is known. */
internal sealed interface RulesetAction {
    data class Update(val id: Long) : RulesetAction
    data object Create : RulesetAction
}

internal fun parseRulesets(payload: String): List<RepoRuleset> = try {
    githubJson.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
        val obj = element.jsonObject
        val id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
        RepoRuleset(id, name)
    }
} catch (_: Exception) {
    emptyList()
}

internal fun parseRulesetName(payload: String): String? = try {
    githubJson.parseToJsonElement(payload).jsonObject["name"]?.jsonPrimitive?.content
} catch (_: Exception) {
    null
}

internal fun isProTierMessage(output: String): Boolean =
    output.contains("Upgrade to GitHub Pro")

/** Existing name wins (PUT); anything else means a new ruleset (POST). */
internal fun decideRulesetAction(existing: List<RepoRuleset>, desiredName: String): RulesetAction =
    existing.firstOrNull { it.name == desiredName }
        ?.let { RulesetAction.Update(it.id) }
        ?: RulesetAction.Create

internal class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val combined: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs one command through the session's execution environment, capturing stdout and stderr.
 *
 * Argument lists only — deliberately never `bash -c` — so the same code works under `cmd.exe`.
 */
internal suspend fun ExecutionContext.runCommand(
    executable: String,
    arguments: List<String>,
    workDir: String? = null
): CommandResult {
    val out = StringBuilder()
    val err = StringBuilder()
    val spec = BashSpec().apply {
        command(executable)
        args(arguments)
        this.workingDir = workDir
        onStdoutLine = { out.appendLine(it) }
        onStderrLine = { err.appendLine(it) }
    }
    val result = executeSpec(spec)
    val code = when (result) {
        is ExecutionResult.Failure -> result.exitCode ?: 1
        else -> 0
    }
    return CommandResult(code, out.toString().trimEnd(), err.toString().trimEnd())
}

/** Surfaces a line to the run's observer so `--dry-run` and progress are visible to the CLI. */
internal fun ExecutionContext.report(line: String) {
    val node = executionNode ?: task ?: return
    observer?.onEvent(ExecutionEvent.TaskOutput(node, line, isError = false))
}

data class GitHubRepoSettingsInputs(
    var repo: String,
    var settingsFile: String,
    var rulesetFile: String,
    var ghBin: String
) : InputSet()

open class GitHubRepoSettings(name: String) : Task<GitHubRepoSettingsInputs, NoOutputs>(name) {
    var repo: String = ""
    var settingsFile: String = ".github/settings/repo-settings.json"
    var rulesetFile: String = ".github/settings/ruleset-main.json"
    var ghBin: String = "gh"

    init {
        topology { requiresCapability("gh") }
        alwaysRun()
    }

    override suspend fun buildInputs(context: ExecutionContext) =
        GitHubRepoSettingsInputs(repo, settingsFile, rulesetFile, ghBin)

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: GitHubRepoSettingsInputs
    ): ExecutionResult<NoOutputs> {
        if (inputSet.repo.isBlank()) {
            return failure(IllegalArgumentException("GitHubRepoSettings requires a 'repo' property (owner/name)."))
        }
        val settings = context.projectDir.resolve(inputSet.settingsFile)
        val ruleset = context.projectDir.resolve(inputSet.rulesetFile)
        if (!Files.exists(settings)) {
            return failure(IllegalArgumentException("Repository settings file not found: $settings"))
        }
        if (!Files.exists(ruleset)) {
            return failure(IllegalArgumentException("Ruleset file not found: $ruleset"))
        }
        val desiredName = parseRulesetName(Files.readString(ruleset))
            ?: return failure(IllegalArgumentException("Ruleset file has no 'name': $ruleset"))

        val patch = listOf("api", "--method", "PATCH", "/repos/${inputSet.repo}", "--input", settings.toString())
        val list = listOf("api", "/repos/${inputSet.repo}/rulesets")

        if (context.dryRun) {
            context.report("[dry-run] ${inputSet.ghBin} ${patch.joinToString(" ")}")
            context.report("[dry-run] ${inputSet.ghBin} ${list.joinToString(" ")}")
            context.report("[dry-run] upsert ruleset '$desiredName' from $ruleset")
            return success()
        }

        context.report("==> Applying repository settings to ${inputSet.repo}...")
        // The shell script ignored this failure on purpose: private free-tier repos reject
        // some settings but the ruleset step below still carries value.
        val patchResult = context.runCommand(inputSet.ghBin, patch)
        if (!patchResult.ok) {
            context.report("⚠️  Repository settings request failed (continuing): ${patchResult.combined}")
        } else {
            context.report("✓ Repository settings applied.")
        }

        context.report("==> Applying branch ruleset to ${inputSet.repo}...")
        val listResult = context.runCommand(inputSet.ghBin, list)

        if (isProTierMessage(listResult.combined)) {
            context.report(
                "⚠️  Repository is private on a personal free tier. Branch rulesets require " +
                    "a public repository or GitHub Pro."
            )
            return success()
        }

        val action = decideRulesetAction(parseRulesets(listResult.stdout), desiredName)
        val upsert = when (action) {
            is RulesetAction.Update ->
                listOf("api", "--method", "PUT", "/repos/${inputSet.repo}/rulesets/${action.id}", "--input", ruleset.toString())
            RulesetAction.Create ->
                listOf("api", "--method", "POST", "/repos/${inputSet.repo}/rulesets", "--input", ruleset.toString())
        }
        val upsertResult = context.runCommand(inputSet.ghBin, upsert)
        if (!upsertResult.ok) {
            return failure(
                RuntimeException("Failed to apply ruleset: ${upsertResult.combined}"),
                upsertResult.exitCode
            )
        }
        when (action) {
            is RulesetAction.Update -> context.report("✓ Updated existing ruleset (ID: ${action.id}).")
            RulesetAction.Create -> context.report("✓ Created new ruleset.")
        }
        return success()
    }
}

data class GitHubWikiSyncInputs(
    var repo: String,
    var sourceDir: String,
    var message: String,
    var gitBin: String
) : InputSet()

open class GitHubWikiSync(name: String) : Task<GitHubWikiSyncInputs, NoOutputs>(name) {
    var repo: String = ""
    var sourceDir: String = "docs/wiki"
    var message: String = "docs: sync wiki documentation from docs/wiki"
    var gitBin: String = "git"

    init {
        topology { requiresCapability("git") }
        alwaysRun()
    }

    override suspend fun buildInputs(context: ExecutionContext) =
        GitHubWikiSyncInputs(repo, sourceDir, message, gitBin)

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: GitHubWikiSyncInputs
    ): ExecutionResult<NoOutputs> {
        if (inputSet.repo.isBlank()) {
            return failure(IllegalArgumentException("GitHubWikiSync requires a 'repo' property (owner/name)."))
        }
        val source = context.projectDir.resolve(inputSet.sourceDir)
        if (!Files.isDirectory(source)) {
            return failure(IllegalArgumentException("Wiki source directory not found: $source"))
        }
        val wikiUrl = "https://github.com/${inputSet.repo}.wiki.git"

        if (context.dryRun) {
            context.report("[dry-run] ${inputSet.gitBin} ls-remote $wikiUrl")
            context.report("[dry-run] ${inputSet.gitBin} clone $wikiUrl <tmp>/wiki")
            context.report("[dry-run] copy $source -> <tmp>/wiki, then commit and push if changed")
            return success()
        }

        context.report("==> Syncing wiki pages from ${inputSet.sourceDir} to GitHub Wiki for ${inputSet.repo}...")
        val tempRoot = Files.createTempDirectory("kartographer-wiki")
        try {
            val lsRemote = context.runCommand(inputSet.gitBin, listOf("ls-remote", wikiUrl))
            if (!lsRemote.ok) {
                return failure(
                    RuntimeException(
                        "The GitHub Wiki repository is not yet initialized on GitHub.\n" +
                            "Please visit https://github.com/${inputSet.repo}/wiki and click " +
                            "'Create the first page' to initialize it, then rerun " +
                            "'kartographer run wiki-sync'."
                    ),
                    lsRemote.exitCode
                )
            }

            val cloneDir = tempRoot.resolve("wiki")
            context.report("==> Cloning wiki repository...")
            val clone = context.runCommand(
                inputSet.gitBin,
                listOf("clone", wikiUrl, cloneDir.toString())
            )
            if (!clone.ok) {
                return failure(RuntimeException("Failed to clone wiki repository: ${clone.combined}"), clone.exitCode)
            }

            context.report("==> Copying updated documentation...")
            copyTree(source, cloneDir)

            val add = context.runCommand(inputSet.gitBin, listOf("add", "."), workDir = cloneDir.toString())
            if (!add.ok) {
                return failure(RuntimeException("git add failed: ${add.combined}"), add.exitCode)
            }

            // `git diff --staged --quiet` exits 1 when there is a diff, which is not an error here.
            val diff = context.runCommand(
                inputSet.gitBin,
                listOf("diff", "--staged", "--quiet"),
                workDir = cloneDir.toString()
            )
            if (diff.exitCode == 0) {
                context.report("✓ Wiki is already up to date.")
                return success()
            }

            val commit = context.runCommand(
                inputSet.gitBin,
                listOf("commit", "-m", inputSet.message),
                workDir = cloneDir.toString()
            )
            if (!commit.ok) {
                return failure(RuntimeException("git commit failed: ${commit.combined}"), commit.exitCode)
            }

            val push = context.runCommand(
                inputSet.gitBin,
                listOf("push", "origin", "HEAD"),
                workDir = cloneDir.toString()
            )
            if (!push.ok) {
                return failure(RuntimeException("git push failed: ${push.combined}"), push.exitCode)
            }
            context.report("✓ Successfully pushed updated wiki documentation to GitHub!")
            return success()
        } catch (t: Throwable) {
            return failure(t)
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                if (relative.toString().isEmpty()) return@forEach
                val target = destination.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
