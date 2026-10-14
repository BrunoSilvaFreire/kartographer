package io.kartographer.tasks.gcp

import io.kartographer.core.exec.CommandResult
import io.kartographer.core.exec.report
import io.kartographer.core.exec.runCommand
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val gcpJson = Json { ignoreUnknownKeys = true }

internal sealed interface EnsureDecision {
    data object Create : EnsureDecision
    data object Reuse : EnsureDecision
    data class Conflict(val diff: Map<String, Pair<String?, String?>>) : EnsureDecision
}

internal fun decideEnsure(existing: Map<String, String?>?, desired: Map<String, String?>): EnsureDecision {
    if (existing == null) return EnsureDecision.Create
    val diff = desired.mapNotNull { (field, expected) ->
        val actual = existing[field]
        if (actual == expected) null else field to (actual to expected)
    }.toMap()
    return if (diff.isEmpty()) EnsureDecision.Reuse else EnsureDecision.Conflict(diff)
}

internal fun parseGcpObject(payload: String): Map<String, String?>? = try {
    gcpJson.parseToJsonElement(payload).jsonObject.mapValues { (_, value) ->
        value.jsonPrimitive.content
    }
} catch (_: Exception) {
    null
}

internal fun parseEnabledServices(payload: String): Set<String> = try {
    gcpJson.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
        element.jsonObject["config"]?.jsonObject?.get("name")?.jsonPrimitive?.content
    }.toSet()
} catch (_: Exception) {
    emptySet()
}

internal fun parsePoolState(payload: String): String? = try {
    gcpJson.parseToJsonElement(payload).jsonObject["state"]?.jsonPrimitive?.content
} catch (_: Exception) {
    null
}

internal fun isMissingResource(output: String): Boolean =
    output.contains("NOT_FOUND", ignoreCase = true) ||
        output.contains("404", ignoreCase = true) ||
        output.contains("not found", ignoreCase = true) ||
        output.contains("unknown service account", ignoreCase = true) ||
        output.contains("not enabled on project", ignoreCase = true) ||
        output.contains("has not been used", ignoreCase = true) ||
        output.contains("is disabled", ignoreCase = true)

internal fun variableDiff(existing: Map<String, String>, desired: Map<String, String>): Map<String, String> =
    desired.filter { (name, value) -> existing[name] != value }

internal fun decideVariableSet(existing: String?, desired: String): Boolean = existing != desired

private fun JsonElement.string(field: String): String? = try {
    jsonObject[field]?.jsonPrimitive?.content
} catch (_: Exception) {
    null
}

private data class GcloudCommand(val args: List<String>, val dryRun: String)

abstract class GcloudTask<I : InputSet, O : OutputSet>(name: String) : Task<I, O>(name) {
    var gcloudBin: String = "gcloud"

    init {
        topology { requiresCapability("gcloud") }
        alwaysRun()
    }

    protected suspend fun command(context: ExecutionContext, args: List<String>): CommandResult =
        context.runCommand(gcloudBin, if ("--quiet" in args) args else args + "--quiet", executeDuringDryRun = true)

    protected fun fail(message: String) = failure(IllegalStateException(message))
}

data class GcloudPreflightInputs(var project: String, var account: String) : InputSet()
class GcloudPreflightOutputs : OutputSet() {
    val account = output<String>("account")
    val projectNumber = output<String>("projectNumber")
}

open class GcloudPreflight(name: String) : GcloudTask<GcloudPreflightInputs, GcloudPreflightOutputs>(name) {
    var project: String = ""
    var account: String = ""

    override suspend fun buildInputs(context: ExecutionContext) = GcloudPreflightInputs(project, account)
    override fun buildOutputs() = GcloudPreflightOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: GcloudPreflightInputs): ExecutionResult<GcloudPreflightOutputs> {
        val authArgs = listOf("auth", "list", "--filter=status:ACTIVE", "--format=value(account)")
        val auth = command(context, authArgs)
        if (!auth.ok || auth.stdout.lineSequence().none { it.trim() == inputSet.account }) {
            return fail("Expected active gcloud account '${inputSet.account}', got '${auth.stdout}'.")
        }
        val projectArgs = listOf("projects", "describe", inputSet.project, "--format=json")
        val result = command(context, projectArgs)
        if (!result.ok) return fail("Could not describe project '${inputSet.project}': ${result.combined}")
        val project = runCatching { gcpJson.parseToJsonElement(result.stdout).jsonObject }.getOrNull()
            ?: return fail("Invalid project JSON for '${inputSet.project}'.")
        if (project["lifecycleState"]?.jsonPrimitive?.content != "ACTIVE") {
            return fail("Project '${inputSet.project}' is not ACTIVE.")
        }
        if (context.dryRun) context.report("[dry-run] verified gcloud account and ACTIVE project ${inputSet.project}")
        return success {
            account.set(inputSet.account)
            projectNumber.set(project["projectNumber"]?.jsonPrimitive?.content ?: "")
        }
    }
}

data class GcloudBillingCheckInputs(var project: String) : InputSet()
class GcloudBillingCheckOutputs : OutputSet() { val billingEnabled = output<Boolean>("billingEnabled") }
open class GcloudBillingCheck(name: String) : GcloudTask<GcloudBillingCheckInputs, GcloudBillingCheckOutputs>(name) {
    var project: String = ""
    override suspend fun buildInputs(context: ExecutionContext) = GcloudBillingCheckInputs(project)
    override fun buildOutputs() = GcloudBillingCheckOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudBillingCheckInputs): ExecutionResult<GcloudBillingCheckOutputs> {
        val result = command(context, listOf("billing", "projects", "describe", inputSet.project, "--format=json"))
        if (!result.ok) return fail("Could not read billing status: ${result.combined}")
        val enabled = runCatching { gcpJson.parseToJsonElement(result.stdout).jsonObject["billingEnabled"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
        if (enabled != true) return fail("Billing is not enabled for project '${inputSet.project}'. Ask an administrator to link billing.")
        if (context.dryRun) context.report("[dry-run] billing is enabled for ${inputSet.project}")
        return success { billingEnabled.set(true) }
    }
}

data class GcloudServicesEnsureInputs(var project: String, var services: List<String>) : InputSet()
class GcloudServicesEnsureOutputs : OutputSet() { val services = output<List<String>>("services") }
open class GcloudServicesEnsure(name: String) : GcloudTask<GcloudServicesEnsureInputs, GcloudServicesEnsureOutputs>(name) {
    var project: String = ""
    var services: List<String> = emptyList()
    override suspend fun buildInputs(context: ExecutionContext) = GcloudServicesEnsureInputs(project, services)
    override fun buildOutputs() = GcloudServicesEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudServicesEnsureInputs): ExecutionResult<GcloudServicesEnsureOutputs> {
        val listArgs = listOf("services", "list", "--enabled", "--project", inputSet.project, "--format=json")
        val listed = command(context, listArgs)
        if (!listed.ok) return fail("Could not list enabled services: ${listed.combined}")
        val enabled = parseEnabledServices(listed.stdout)
        val missing = inputSet.services.filterNot(enabled::contains)
        if (context.dryRun) missing.forEach { context.report("[dry-run] gcloud services enable $it --project ${inputSet.project}") }
        for (service in missing) {
            val args = listOf("services", "enable", service, "--project", inputSet.project, "--quiet")
            if (context.dryRun) continue
            val result = command(context, args)
            if (!result.ok) return fail("Could not enable $service: ${result.combined}")
        }
        return success { services.set(inputSet.services) }
    }
}

data class GcloudServiceAccountEnsureInputs(var project: String, var accountId: String, var displayName: String) : InputSet()
class GcloudServiceAccountEnsureOutputs : OutputSet() { val email = output<String>("email") }
open class GcloudServiceAccountEnsure(name: String) : GcloudTask<GcloudServiceAccountEnsureInputs, GcloudServiceAccountEnsureOutputs>(name) {
    var project: String = ""
    var accountId: String = ""
    var displayName: String = ""
    override suspend fun buildInputs(context: ExecutionContext) = GcloudServiceAccountEnsureInputs(project, accountId, displayName)
    override fun buildOutputs() = GcloudServiceAccountEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudServiceAccountEnsureInputs): ExecutionResult<GcloudServiceAccountEnsureOutputs> {
        val email = "${inputSet.accountId}@${inputSet.project}.iam.gserviceaccount.com"
        val describeArgs = listOf("iam", "service-accounts", "describe", email, "--project", inputSet.project, "--format=json")
        val current = command(context, describeArgs)
        val createArgs = listOf("iam", "service-accounts", "create", inputSet.accountId, "--project", inputSet.project, "--display-name", inputSet.displayName, "--quiet")
        if (!current.ok && !current.combined.contains("NOT_FOUND", ignoreCase = true)) return fail("Could not describe service account $email: ${current.combined}")
        if (current.ok) {
            val actual = runCatching { gcpJson.parseToJsonElement(current.stdout).jsonObject["displayName"]?.jsonPrimitive?.content }.getOrNull()
            if (actual != inputSet.displayName) return fail("conflict: service account $email displayName expected '${inputSet.displayName}', found '$actual'")
            context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} service account $email")
        } else if (context.dryRun) context.report("[dry-run] create service account $email")
        else {
            val created = command(context, createArgs)
            if (!created.ok) return fail("Could not create service account $email: ${created.combined}")
            context.report("created service account $email")
        }
        return success { this.email.set(email) }
    }
}

data class GcloudWorkloadIdentityPoolEnsureInputs(var project: String, var poolId: String, var displayName: String) : InputSet()
class GcloudWorkloadIdentityPoolEnsureOutputs : OutputSet() { val name = output<String>("name") }
open class GcloudWorkloadIdentityPoolEnsure(name: String) : GcloudTask<GcloudWorkloadIdentityPoolEnsureInputs, GcloudWorkloadIdentityPoolEnsureOutputs>(name) {
    var project: String = ""
    var poolId: String = ""
    var displayName: String = ""
    override suspend fun buildInputs(context: ExecutionContext) = GcloudWorkloadIdentityPoolEnsureInputs(project, poolId, displayName)
    override fun buildOutputs() = GcloudWorkloadIdentityPoolEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudWorkloadIdentityPoolEnsureInputs): ExecutionResult<GcloudWorkloadIdentityPoolEnsureOutputs> {
        val fullName = "projects/${inputSet.project}/locations/global/workloadIdentityPools/${inputSet.poolId}"
        val descArgs = listOf("iam", "workload-identity-pools", "describe", inputSet.poolId, "--location=global", "--project", inputSet.project, "--format=json")
        val current = command(context, descArgs)
        if (!current.ok && !current.combined.contains("NOT_FOUND", ignoreCase = true)) return fail("Could not describe workload identity pool: ${current.combined}")
        if (current.ok) {
            val state = parsePoolState(current.stdout)
            if (state == "DELETED") return fail("conflict: workload identity pool $fullName is DELETED; undelete it, then rerun")
            val actual = runCatching { gcpJson.parseToJsonElement(current.stdout).jsonObject["displayName"]?.jsonPrimitive?.content }.getOrNull()
            if (actual != inputSet.displayName) return fail("conflict: pool $fullName displayName expected '${inputSet.displayName}', found '$actual'")
            context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} workload identity pool $fullName")
        } else {
            val createArgs = listOf("iam", "workload-identity-pools", "create", inputSet.poolId, "--location=global", "--project", inputSet.project, "--display-name", inputSet.displayName, "--quiet")
            if (context.dryRun) context.report("[dry-run] gcloud ${createArgs.joinToString(" ")}") else {
                val created = command(context, createArgs)
                if (!created.ok) return fail("Could not create workload identity pool: ${created.combined}")
                context.report("created workload identity pool $fullName")
            }
        }
        return success { this.name.set(fullName) }
    }
}

data class GcloudOidcProviderEnsureInputs(
    var project: String, var poolId: String, var providerId: String, var issuerUri: String,
    var attributeMapping: Map<String, String>, var attributeCondition: String
) : InputSet()
class GcloudOidcProviderEnsureOutputs : OutputSet() { val name = output<String>("name") }
open class GcloudOidcProviderEnsure(name: String) : GcloudTask<GcloudOidcProviderEnsureInputs, GcloudOidcProviderEnsureOutputs>(name) {
    var project: String = ""
    var poolId: String = ""
    var providerId: String = ""
    var issuerUri: String = "https://token.actions.githubusercontent.com"
    var attributeMapping: Map<String, String> = emptyMap()
    var attributeCondition: String = ""
    override suspend fun buildInputs(context: ExecutionContext) = GcloudOidcProviderEnsureInputs(project, poolId, providerId, issuerUri, attributeMapping, attributeCondition)
    override fun buildOutputs() = GcloudOidcProviderEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudOidcProviderEnsureInputs): ExecutionResult<GcloudOidcProviderEnsureOutputs> {
        val fullName = "projects/${inputSet.project}/locations/global/workloadIdentityPools/${inputSet.poolId}/providers/${inputSet.providerId}"
        val descArgs = listOf("iam", "workload-identity-pools", "providers", "describe", inputSet.providerId, "--workload-identity-pool", inputSet.poolId, "--location=global", "--project", inputSet.project, "--format=json")
        val current = command(context, descArgs)
        if (!current.ok && !current.combined.contains("NOT_FOUND", ignoreCase = true)) return fail("Could not describe provider $fullName: ${current.combined}")
        if (current.ok) {
            val json = runCatching { gcpJson.parseToJsonElement(current.stdout).jsonObject }.getOrNull()
                ?: return fail("Invalid provider JSON for $fullName")
            val actualMap = (json["attributeMapping"] as? kotlinx.serialization.json.JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val issuer = json["oidc"]?.jsonObject?.get("issuerUri")?.jsonPrimitive?.content
            val condition = json.string("attributeCondition")
            val diffs = buildList {
                if (issuer != inputSet.issuerUri) add("issuerUri expected '${inputSet.issuerUri}', found '$issuer'")
                if (actualMap != inputSet.attributeMapping) add("attributeMapping expected ${inputSet.attributeMapping}, found $actualMap")
                if (condition != inputSet.attributeCondition) add("attributeCondition expected '${inputSet.attributeCondition}', found '$condition'")
            }
            if (diffs.isNotEmpty()) return fail("conflict: $fullName ${diffs.joinToString("; ")}")
            context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} provider $fullName")
        } else {
            val mapping = inputSet.attributeMapping.entries.joinToString(",") { "${it.key}=${it.value}" }
            val args = listOf("iam", "workload-identity-pools", "providers", "create-oidc", inputSet.providerId, "--workload-identity-pool", inputSet.poolId, "--location=global", "--project", inputSet.project, "--issuer-uri", inputSet.issuerUri, "--attribute-mapping", mapping, "--attribute-condition", inputSet.attributeCondition, "--quiet")
            if (context.dryRun) context.report("[dry-run] gcloud ${args.joinToString(" ")}") else {
                val created = command(context, args)
                if (!created.ok) return fail("Could not create provider $fullName: ${created.combined}")
                context.report("created provider $fullName")
            }
        }
        return success { this.name.set(fullName) }
    }
}

data class GcloudStorageBucketEnsureInputs(var project: String, var bucket: String, var location: String) : InputSet()
class GcloudStorageBucketEnsureOutputs : OutputSet() { val url = output<String>("url") }
open class GcloudStorageBucketEnsure(name: String) : GcloudTask<GcloudStorageBucketEnsureInputs, GcloudStorageBucketEnsureOutputs>(name) {
    var project: String = ""
    var bucket: String = ""
    var location: String = "us-central1"
    override suspend fun buildInputs(context: ExecutionContext) = GcloudStorageBucketEnsureInputs(project, bucket, location)
    override fun buildOutputs() = GcloudStorageBucketEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudStorageBucketEnsureInputs): ExecutionResult<GcloudStorageBucketEnsureOutputs> {
        val url = "gs://${inputSet.bucket}"
        val current = command(context, listOf("storage", "buckets", "describe", url, "--format=json"))
        if (!current.ok && !current.combined.contains("404", ignoreCase = true) && !current.combined.contains("not found", ignoreCase = true)) return fail("Could not describe $url: ${current.combined}")
        if (current.ok) {
            val json = runCatching { gcpJson.parseToJsonElement(current.stdout).jsonObject }.getOrNull() ?: return fail("Invalid bucket JSON for $url")
            val actualLocation = json["location"]?.jsonPrimitive?.content
            val versioning = json["versioning"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
            val ubla = json["iamConfiguration"]?.jsonObject?.get("uniformBucketLevelAccess")?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
            val pap = json["iamConfiguration"]?.jsonObject?.get("publicAccessPrevention")?.jsonPrimitive?.content == "enforced"
            val diff = listOfNotNull(
                if (actualLocation.equals(inputSet.location, ignoreCase = true)) null else "location expected ${inputSet.location}, found $actualLocation",
                if (versioning) null else "versioning is disabled",
                if (ubla) null else "uniform bucket-level access is disabled",
                if (pap) null else "public access prevention is not enforced"
            )
            if (diff.isNotEmpty()) return fail("conflict: bucket $url ${diff.joinToString("; ")}")
            context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} bucket $url")
        } else {
            val args = listOf("storage", "buckets", "create", url, "--project", inputSet.project, "--location", inputSet.location, "--uniform-bucket-level-access", "--public-access-prevention", "--quiet")
            val versioningArgs = listOf("storage", "buckets", "update", url, "--versioning", "--quiet")
            if (context.dryRun) {
                context.report("[dry-run] gcloud ${args.joinToString(" ")}")
                context.report("[dry-run] gcloud ${versioningArgs.joinToString(" ")}")
            } else {
                val created = command(context, args)
                if (!created.ok) return fail("Could not create bucket $url: ${created.combined}")
                val versioningEnabled = command(context, versioningArgs)
                if (!versioningEnabled.ok) return fail("Could not enable versioning on $url: ${versioningEnabled.combined}")
                context.report("created bucket $url")
            }
        }
        return success { this.url.set(url) }
    }
}

data class GcloudKmsKeyEnsureInputs(var project: String, var location: String, var keyRing: String, var key: String) : InputSet()
class GcloudKmsKeyEnsureOutputs : OutputSet() { val name = output<String>("name") }
open class GcloudKmsKeyEnsure(name: String) : GcloudTask<GcloudKmsKeyEnsureInputs, GcloudKmsKeyEnsureOutputs>(name) {
    var project: String = ""
    var location: String = "global"
    var keyRing: String = "pulumi"
    var key: String = "state"
    override suspend fun buildInputs(context: ExecutionContext) = GcloudKmsKeyEnsureInputs(project, location, keyRing, key)
    override fun buildOutputs() = GcloudKmsKeyEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudKmsKeyEnsureInputs): ExecutionResult<GcloudKmsKeyEnsureOutputs> {
        val ring = "projects/${inputSet.project}/locations/${inputSet.location}/keyRings/${inputSet.keyRing}"
        val fullName = "$ring/cryptoKeys/${inputSet.key}"
        val ringDesc = command(context, listOf("kms", "keyrings", "describe", inputSet.keyRing, "--location", inputSet.location, "--project", inputSet.project, "--format=json"))
        if (!ringDesc.ok && !(context.dryRun && isMissingResource(ringDesc.combined))) return fail("Could not describe key ring $ring: ${ringDesc.combined}")
        if (!ringDesc.ok) {
            val args = listOf("kms", "keyrings", "create", inputSet.keyRing, "--location", inputSet.location, "--project", inputSet.project, "--quiet")
            if (context.dryRun) context.report("[dry-run] gcloud ${args.joinToString(" ")}") else {
                val created = command(context, args)
                if (!created.ok) return fail("Could not create key ring $ring: ${created.combined}")
            }
        } else context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} key ring $ring")
        val keyDesc = command(context, listOf("kms", "keys", "describe", inputSet.key, "--keyring", inputSet.keyRing, "--location", inputSet.location, "--project", inputSet.project, "--format=json"))
        if (!keyDesc.ok && !(context.dryRun && isMissingResource(keyDesc.combined))) return fail("Could not describe key $fullName: ${keyDesc.combined}")
        if (!keyDesc.ok) {
            val args = listOf("kms", "keys", "create", inputSet.key, "--keyring", inputSet.keyRing, "--location", inputSet.location, "--project", inputSet.project, "--purpose", "encryption", "--quiet")
            if (context.dryRun) context.report("[dry-run] gcloud ${args.joinToString(" ")}") else {
                val created = command(context, args)
                if (!created.ok) return fail("Could not create key $fullName: ${created.combined}")
            }
        } else {
            val purpose = runCatching { gcpJson.parseToJsonElement(keyDesc.stdout).jsonObject["purpose"]?.jsonPrimitive?.content }.getOrNull()
            if (purpose != "ENCRYPT_DECRYPT") return fail("conflict: KMS key $fullName purpose expected ENCRYPT_DECRYPT, found '$purpose'")
            context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} key $fullName")
        }
        return success { name.set(fullName) }
    }
}

data class GcloudIamBindingEnsureInputs(var project: String, var scope: String, var resource: String, var role: String, var member: String) : InputSet()
class GcloudIamBindingEnsureOutputs : OutputSet() { val present = output<Boolean>("present") }
open class GcloudIamBindingEnsure(name: String) : GcloudTask<GcloudIamBindingEnsureInputs, GcloudIamBindingEnsureOutputs>(name) {
    var project: String = ""
    var scope: String = "project"
    var role: String = ""
    val resource = property<String>()
    val member = property<String>()
    override suspend fun buildInputs(context: ExecutionContext) = GcloudIamBindingEnsureInputs(project, scope, resource.get(), role, member.get())
    override fun buildOutputs() = GcloudIamBindingEnsureOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: GcloudIamBindingEnsureInputs): ExecutionResult<GcloudIamBindingEnsureOutputs> {
        val policyArgs = when (inputSet.scope) {
            "project" -> listOf("projects", "get-iam-policy", inputSet.project, "--format=json")
            "serviceAccount" -> listOf("iam", "service-accounts", "get-iam-policy", inputSet.resource, "--project", inputSet.project, "--format=json")
            "bucket" -> listOf("storage", "buckets", "get-iam-policy", "gs://${inputSet.resource}", "--format=json")
            "kmsKey" -> listOf("kms", "keys", "get-iam-policy", inputSet.resource.substringAfterLast("/cryptoKeys/"), "--keyring", inputSet.resource.substringAfter("/keyRings/").substringBefore("/"), "--location", inputSet.resource.substringAfter("/locations/").substringBefore("/"), "--project", inputSet.project, "--format=json")
            else -> return fail("Unknown IAM scope '${inputSet.scope}'.")
        }
        val policy = command(context, policyArgs)
        if (!policy.ok) {
            if (context.dryRun && inputSet.scope != "project" && isMissingResource(policy.combined)) {
                context.report("[dry-run] planned IAM ${inputSet.role} for ${inputSet.member} after ${inputSet.resource} is created")
                return success { present.set(true) }
            }
            return fail("Could not read IAM policy for ${inputSet.resource}: ${policy.combined}")
        }
        val bindings = runCatching { gcpJson.parseToJsonElement(policy.stdout).jsonObject["bindings"]?.jsonArray.orEmpty() }.getOrNull().orEmpty()
        val present = bindings.any { binding ->
            binding.jsonObject["role"]?.jsonPrimitive?.content == inputSet.role &&
                binding.jsonObject["members"]?.jsonArray?.any { it.jsonPrimitive.content == inputSet.member } == true
        }
        if (present) context.report("${if (context.dryRun) "[dry-run] reuse" else "reused"} IAM ${inputSet.role} for ${inputSet.member}")
        else {
            val args = when (inputSet.scope) {
                "project" -> listOf("projects", "add-iam-policy-binding", inputSet.project, "--member", inputSet.member, "--role", inputSet.role, "--quiet")
                "serviceAccount" -> listOf("iam", "service-accounts", "add-iam-policy-binding", inputSet.resource, "--project", inputSet.project, "--member", inputSet.member, "--role", inputSet.role, "--quiet")
                "bucket" -> listOf("storage", "buckets", "add-iam-policy-binding", "gs://${inputSet.resource}", "--member", inputSet.member, "--role", inputSet.role, "--quiet")
                else -> listOf("kms", "keys", "add-iam-policy-binding", inputSet.resource.substringAfterLast("/cryptoKeys/"), "--keyring", inputSet.resource.substringAfter("/keyRings/").substringBefore("/"), "--location", inputSet.resource.substringAfter("/locations/").substringBefore("/"), "--project", inputSet.project, "--member", inputSet.member, "--role", inputSet.role, "--quiet")
            }
            if (context.dryRun) context.report("[dry-run] gcloud ${args.joinToString(" ")}") else {
                val added = command(context, args)
                if (!added.ok) return fail("Could not add IAM binding: ${added.combined}")
                context.report("created IAM binding ${inputSet.role} for ${inputSet.member}")
            }
        }
        return success { this.present.set(true) }
    }
}
