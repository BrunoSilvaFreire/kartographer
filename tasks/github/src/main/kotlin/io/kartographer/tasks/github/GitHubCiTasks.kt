package io.kartographer.tasks.github

import io.kartographer.core.exec.report
import io.kartographer.core.exec.runCommand
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

internal fun parseVariables(payload: String): Map<String, String> = try {
    githubJson.parseToJsonElement(payload).jsonArray.associate { element ->
        val obj = element.jsonObject
        obj["name"]!!.jsonPrimitive.content to obj["value"]!!.jsonPrimitive.content
    }
} catch (_: Exception) {
    emptyMap()
}

internal fun variableChanges(existing: Map<String, String>, desired: Map<String, String>): Map<String, String> =
    desired.filter { (name, value) -> existing[name] != value }

data class GitHubVariablesEnsureInputs(var repo: String, var variables: Map<String, String>, var ghBin: String) : InputSet()

open class GitHubVariablesEnsure(name: String) : Task<GitHubVariablesEnsureInputs, NoOutputs>(name) {
    var repo: String = ""
    var ghBin: String = "gh"
    val projectId = property<String>()
    val projectNumber = property<String>()
    val region = property<String>()
    val workloadIdentityProvider = property<String>()
    val deployServiceAccount = property<String>()
    val previewServiceAccount = property<String>()
    val pulumiBackendUrl = property<String>()
    val pulumiSecretsProvider = property<String>()

    init { topology { requiresCapability("gh") }; alwaysRun() }

    override suspend fun buildInputs(context: ExecutionContext) = GitHubVariablesEnsureInputs(
        repo,
        mapOf(
            "GCP_PROJECT_ID" to projectId.get(),
            "GCP_PROJECT_NUMBER" to projectNumber.get(),
            "GCP_REGION" to region.get(),
            "GCP_WORKLOAD_IDENTITY_PROVIDER" to workloadIdentityProvider.get(),
            "GCP_DEPLOY_SERVICE_ACCOUNT" to deployServiceAccount.get(),
            "GCP_PREVIEW_SERVICE_ACCOUNT" to previewServiceAccount.get(),
            "PULUMI_BACKEND_URL" to pulumiBackendUrl.get(),
            "PULUMI_SECRETS_PROVIDER" to pulumiSecretsProvider.get()
        ),
        ghBin
    )
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: GitHubVariablesEnsureInputs): ExecutionResult<NoOutputs> {
        if (inputSet.repo.isBlank()) return failure(IllegalArgumentException("GitHubVariablesEnsure requires a 'repo' property (owner/name)."))
        val listArgs = listOf("variable", "list", "--repo", inputSet.repo, "--json", "name,value")
        val listed = context.runCommand(inputSet.ghBin, listArgs, executeDuringDryRun = true)
        if (!listed.ok) return failure(IllegalStateException("Could not list variables: ${listed.combined}"), listed.exitCode)
        val changed = variableChanges(parseVariables(listed.stdout), inputSet.variables)
        if (context.dryRun) {
            context.report("[dry-run] ${inputSet.ghBin} ${listArgs.joinToString(" ")}")
            changed.keys.forEach { name -> context.report("[dry-run] ${inputSet.ghBin} variable set $name --repo ${inputSet.repo} --body <value>") }
            return success()
        }
        for ((name, value) in changed) {
            val result = context.runCommand(inputSet.ghBin, listOf("variable", "set", name, "--repo", inputSet.repo, "--body", value))
            if (!result.ok) return failure(IllegalStateException("Could not set variable '$name': ${result.combined}"), result.exitCode)
            context.report("set repository variable $name")
        }
        if (changed.isEmpty()) context.report("GitHub variables already match.")
        return success()
    }
}

data class GitHubWorkflowRunInputs(var repo: String, var workflow: String, var ref: String, var ghBin: String) : InputSet()

class GitHubWorkflowRunOutputs : OutputSet() {
    val runId = output<Long>("runId")
    val url = output<String>("url")
}

open class GitHubWorkflowRun(name: String) : Task<GitHubWorkflowRunInputs, GitHubWorkflowRunOutputs>(name) {
    var repo: String = ""
    var workflow: String = ""
    var ref: String = "main"
    var ghBin: String = "gh"

    init { topology { requiresCapability("gh") }; alwaysRun() }

    override suspend fun buildInputs(context: ExecutionContext) = GitHubWorkflowRunInputs(repo, workflow, ref, ghBin)
    override fun buildOutputs() = GitHubWorkflowRunOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: GitHubWorkflowRunInputs): ExecutionResult<GitHubWorkflowRunOutputs> {
        if (inputSet.repo.isBlank() || inputSet.workflow.isBlank() || inputSet.ref.isBlank()) {
            return failure(IllegalArgumentException("GitHubWorkflowRun requires repo, workflow, and ref."))
        }
        val dispatch = listOf("workflow", "run", inputSet.workflow, "--ref", inputSet.ref, "--repo", inputSet.repo)
        if (context.dryRun) {
            context.report("[dry-run] ${inputSet.ghBin} ${dispatch.joinToString(" ")}")
            context.report("[dry-run] ${inputSet.ghBin} run watch <run-id> --exit-status --repo ${inputSet.repo}")
            return success()
        }
        val startedAt = Instant.now().minusSeconds(2)
        val triggered = context.runCommand(inputSet.ghBin, dispatch)
        if (!triggered.ok) return failure(IllegalStateException("Could not dispatch workflow: ${triggered.combined}"), triggered.exitCode)
        var workflowRunId: Long? = null
        var runUrl: String? = null
        for (attempt in 0 until 30) {
            val listed = context.runCommand(inputSet.ghBin, listOf(
                "run", "list", "--workflow", inputSet.workflow, "--branch", inputSet.ref, "--event", "workflow_dispatch",
                "--limit", "5", "--json", "databaseId,url,createdAt,headBranch", "--repo", inputSet.repo
            ))
            if (!listed.ok) return failure(IllegalStateException("Could not find dispatched workflow run: ${listed.combined}"), listed.exitCode)
            val candidate = runCatching {
                githubJson.parseToJsonElement(listed.stdout).jsonArray.firstOrNull { item ->
                    val obj = item.jsonObject
                    obj["headBranch"]?.jsonPrimitive?.content == inputSet.ref &&
                        Instant.parse(obj["createdAt"]!!.jsonPrimitive.content) >= startedAt
                }?.jsonObject
            }.getOrNull()
            if (candidate != null) {
                workflowRunId = candidate["databaseId"]?.jsonPrimitive?.content?.toLongOrNull()
                runUrl = candidate["url"]?.jsonPrimitive?.content
                if (workflowRunId != null && runUrl != null) break
            }
            if (attempt < 29) kotlinx.coroutines.delay(1000)
        }
        val id = workflowRunId ?: return failure(IllegalStateException("Dispatched workflow run was not found."))
        val watched = context.runCommand(inputSet.ghBin, listOf("run", "watch", id.toString(), "--exit-status", "--repo", inputSet.repo))
        if (!watched.ok) return failure(IllegalStateException("Workflow run $id failed: ${watched.combined}"), watched.exitCode)
        context.report("workflow completed: $runUrl")
        return success { runId.set(id); url.set(runUrl!!) }
    }
}
