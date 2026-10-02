package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash
import io.kartographer.core.scheduler.TaskStateStore
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// Elevation is not handled per-client: when a run needs privileges, wrap the
// whole invocation as `llm-sudo <karto> ...` so karto itself runs under sudo.
enum class ContainerClient(val command: String, val rootful: Boolean) {
    DOCKER("docker", false),
    NERDCTL("nerdctl", false),
    NERDCTL_ROOTFUL("sudo nerdctl", true)
}

/**
 * Builds a container image from a Containerfile/Dockerfile.
 */
data class ContainerBuildInputs(
    var imageName: String,
    var containerfile: String,
    var context: String,
    var platforms: List<String>,
    var containerClient: ContainerClient,
    var version: String?,
    var namespace: String?,
    var buildArgs: Map<String, String>
) : InputSet()

class ContainerBuildOutputs : OutputSet() {
    val imageRef = output<String>("image")
    val sourceFingerprint = output<String>("source-fingerprint")
    val containerfilePath = output<String>("containerfile")
    val contextPath = output<String>("context")
}

open class ContainerBuild(name: String) : Task<ContainerBuildInputs, ContainerBuildOutputs>(name) {
    val imageName = property<String>()
    val containerfile = property<String>()
    val context = property<String> { set(".") }
    val platforms = property<List<String>> { set(emptyList()) }
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val version = property<String>().withCLI("version", "Override the build version")
    val namespace = property<String>()
    val buildArgs = property<Map<String, String>>()

    // Elevation is decided while the plan is built, before any provider can
    // resolve, so only a directly-set client can drive it.
    override val requiresElevation: Boolean
        get() = containerClient.directOrNull()?.rootful ?: false

    init {
        upToDateWhen { ctx, task ->
            val state = TaskStateStore.loadState(ctx, task) ?: return@upToDateWhen false
            val image = state.outputs["image"]?.value ?: return@upToDateWhen false
            val source = state.outputs["source-fingerprint"]?.value ?: return@upToDateWhen false
            val containerfile = state.outputs["containerfile"]?.value ?: return@upToDateWhen false
            val context = state.outputs["context"]?.value ?: return@upToDateWhen false
            state.inputHash == ctx.inputHash &&
                source == buildSourceFingerprint(ctx.projectDir, containerfile, context) &&
                localImageExists(ctx, image)
        }
    }

    override suspend fun buildInputs(context: ExecutionContext): ContainerBuildInputs {
        val effectiveVersion = version.orNull()
        val effectiveBuildArgs = buildArgs.orNull()?.toMutableMap() ?: linkedMapOf()
        if (effectiveVersion != null && "PROJECT_VERSION" in effectiveBuildArgs) {
            effectiveBuildArgs["PROJECT_VERSION"] = effectiveVersion
        }
        return ContainerBuildInputs(
            imageName.get(), containerfile.get(), this.context.get(), platforms.get(),
            containerClient.get(), effectiveVersion, namespace.orNull(), effectiveBuildArgs
        )
    }

    override fun buildOutputs() = ContainerBuildOutputs()

    // TODO: platforms/namespace/containerClient are read at graph time via directOrNull(); persist them in state (like containerfile/context) so provider-backed values are checked correctly.
    protected open fun localImageExists(context: ExecutionContext, ref: String): Boolean =
        platforms.directOrNull().orEmpty().ifEmpty { listOf("") }.all { platform ->
            runContainerCommand(
                containerClient.directOrNull() ?: ContainerClient.DOCKER,
                namespace.directOrNull(),
                listOf("image", "inspect") +
                    (if (platform.isEmpty()) emptyList() else listOf("--platform", platform)) +
                    ref,
            ) != null
        }

    override suspend fun execute(context: ExecutionContext, inputSet: ContainerBuildInputs): ExecutionResult<ContainerBuildOutputs> {
        val clientCmd = inputSet.containerClient.command
        val fullImageName = inputSet.version?.let { "${inputSet.imageName}:$it" } ?: inputSet.imageName
        val configuredBuildArgs = inputSet.buildArgs
        val effectiveBuildArgs = linkedMapOf<String, String>()
        if (inputSet.version != null && "VERSION" !in configuredBuildArgs) {
            effectiveBuildArgs["VERSION"] = inputSet.version!!
        }
        val sourceRevision = configuredBuildArgs["GIT_COMMIT"]
            ?: context.environment["GIT_COMMIT"]
            ?: gitRevision(context.projectDir)
        if (sourceRevision != null) {
            if ("GIT_COMMIT" !in configuredBuildArgs) {
                effectiveBuildArgs["GIT_COMMIT"] = sourceRevision
            }
            if ("BUILD_DATE" !in configuredBuildArgs) {
                effectiveBuildArgs["BUILD_DATE"] =
                    context.environment["BUILD_DATE"] ?: Instant.now().toString()
            }
        }
        effectiveBuildArgs.putAll(configuredBuildArgs)

        val commandResult = context.executeBash {
            requiresElevation = inputSet.containerClient.rootful
            command(clientCmd)
            if (inputSet.namespace != null && clientCmd.contains("nerdctl")) {
                args("--namespace", inputSet.namespace!!)
            }
            args("build", "-t", fullImageName, "-f", inputSet.containerfile)
            effectiveBuildArgs.forEach { (k, v) -> args("--build-arg", "$k=$v") }
            inputSet.platforms.forEach { args("--platform", it) }
            args(inputSet.context)
        }
        return when (commandResult) {
            is ExecutionResult.Failure -> failure(commandResult.error, commandResult.exitCode)
            else -> success {
                imageRef.set(fullImageName)
                sourceFingerprint.set(buildSourceFingerprint(context.projectDir, inputSet.containerfile, inputSet.context))
                containerfilePath.set(inputSet.containerfile)
                contextPath.set(inputSet.context)
            }
        }
    }
}

/**
 * Pushes a container image to a registry.
 */
data class ContainerPushInputs(
    var imageRef: String,
    var registry: String,
    var containerClient: ContainerClient,
    var namespace: String?
) : InputSet()

class ContainerPushOutputs : OutputSet() {
    val pushedRef = output<String>("pushed")
}

open class ContainerPush(name: String) : Task<ContainerPushInputs, ContainerPushOutputs>(name) {
    val imageRef = property<String>()
    val registry = property<String>()
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val namespace = property<String>()
    // Control-only: this task's own execution pushes whatever `imageRef` the
    // build produced. The version is forwarded to the build as a parametrized
    // dependency (see containerMatrix), so `push/<image> --version=X` tags the
    // image X instead of the matrix-wide RELEASE_TAG.
    val version = property<String>().withCLI("version", "Override the image version to build and push")

    // Elevation is decided while the plan is built, before any provider can
    // resolve, so only a directly-set client can drive it.
    override val requiresElevation: Boolean
        get() = containerClient.directOrNull()?.rootful ?: false

    init {
        upToDateWhen { context, task ->
            val state = TaskStateStore.loadState(context, task) ?: return@upToDateWhen false
            val pushed = state.outputs["pushed"]?.value ?: return@upToDateWhen false
            state.inputHash == context.inputHash && registryDigestMatches(pushed)
        }
    }

    override suspend fun buildInputs(context: ExecutionContext) = ContainerPushInputs(
        imageRef.get(), registry.get(), containerClient.get(), namespace.orNull()
    )

    override fun buildOutputs() = ContainerPushOutputs()

    protected open fun registryDigestMatches(fullRef: String): Boolean {
        val client = containerClient.directOrNull() ?: ContainerClient.DOCKER
        val namespace = namespace.directOrNull()
        val local = localImageDigest(client, namespace, fullRef) ?: return false
        val remote = remoteManifestDigest(client, namespace, fullRef) ?: return false
        return local == remote
    }

    protected open fun localImageDigest(client: ContainerClient, namespace: String?, ref: String): String? =
        containerLocalImageDigest(client, namespace, ref)

    protected open fun remoteManifestDigest(client: ContainerClient, namespace: String?, ref: String): String? =
        containerRemoteManifestDigest(client, namespace, ref)

    override suspend fun execute(context: ExecutionContext, inputSet: ContainerPushInputs): ExecutionResult<ContainerPushOutputs> {
        val clientCmd = inputSet.containerClient.command
        val fullRef = if (inputSet.registry.isEmpty()) inputSet.imageRef else "${inputSet.registry}/${inputSet.imageRef}"

        if (fullRef != inputSet.imageRef) {
            val tagResult = context.executeBash {
                requiresElevation = inputSet.containerClient.rootful
                command(clientCmd)
                if (inputSet.namespace != null && clientCmd.contains("nerdctl")) {
                    args("--namespace", inputSet.namespace!!)
                }
                args("tag", inputSet.imageRef, fullRef)
            }
            if (tagResult is ExecutionResult.Failure) return failure(tagResult.error, tagResult.exitCode)
        }

        val pushResult = context.executeBash {
            requiresElevation = inputSet.containerClient.rootful
            command(clientCmd)
            if (inputSet.namespace != null && clientCmd.contains("nerdctl")) {
                args("--namespace", inputSet.namespace!!)
            }
            args("push")
            if (clientCmd.contains("nerdctl")) {
                args("--all-platforms")
            }
            args(fullRef)
        }
        return when (pushResult) {
            is ExecutionResult.Failure -> failure(pushResult.error, pushResult.exitCode)
            else -> success { pushedRef.set(fullRef) }
        }
    }
}

private fun sha256(path: Path): String = try {
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
} catch (_: Exception) {
    ""
}

internal fun buildSourceFingerprint(projectDir: Path, containerfile: String, context: String): String {
    val containerfilePath = projectDir.resolve(containerfile)
    val contextPath = projectDir.resolve(context)
    return sha256(containerfilePath) + ":" + contextFingerprint(contextPath)
}

private fun contextFingerprint(context: Path): String = try {
    val root = commandOutput(context, listOf("git", "rev-parse", "--show-toplevel"))?.trim()?.let(Path::of) ?: return ""
    // A context equal to the repository root relativizes to the empty string, and an empty
    // pathspec makes `git diff` fail (which would silently degrade this fingerprint to nothing).
    val relative = root.relativize(context.toAbsolutePath()).toString().ifEmpty { "." }
    val revision = commandOutput(root, listOf("git", "rev-parse", "HEAD")) ?: return ""
    val diff = commandOutput(root, listOf("git", "diff", "HEAD", "--binary", "--", relative)) ?: return ""
    val untracked = commandOutput(root, listOf("git", "ls-files", "--others", "--exclude-standard", "-z", "--", relative))
        ?.split('\u0000')?.filter(String::isNotEmpty)?.sorted().orEmpty()
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(revision.toByteArray(StandardCharsets.UTF_8))
    digest.update(diff.toByteArray(StandardCharsets.UTF_8))
    untracked.forEach { file ->
        digest.update(file.toByteArray(StandardCharsets.UTF_8))
        digest.update(Files.readAllBytes(root.resolve(file)))
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (_: Exception) {
    ""
}

private fun containerLocalImageDigest(client: ContainerClient, namespace: String?, ref: String): String? =
    runContainerCommand(client, namespace, listOf("image", "inspect", "--mode=native", "--format", "{{.Target.Digest}}", ref))
        ?.trim()?.takeIf { it.startsWith("sha256:") }

private fun containerRemoteManifestDigest(client: ContainerClient, namespace: String?, ref: String): String? =
    runContainerCommand(client, namespace, listOf("manifest", "inspect", "--verbose", ref))
        ?.let(::remoteDescriptorDigest)

internal fun remoteDescriptorDigest(output: String): String? = Regex(
    "\\\"Descriptor\\\"\\s*:\\s*\\{[^}]*\\\"digest\\\"\\s*:\\s*\\\"(sha256:[0-9a-f]{64})\\\"",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
).find(output)?.groupValues?.get(1)

private fun runContainerCommand(client: ContainerClient, namespace: String?, args: List<String>): String? {
    val command = client.command.split(" ").toMutableList()
    if (namespace != null && client.command.contains("nerdctl")) command += listOf("--namespace", namespace)
    command += args
    return commandOutput(null, command)
}

private fun commandOutput(directory: Path?, command: List<String>): String? = try {
    val process = ProcessBuilder(command).apply {
        directory?.let { directory(it.toFile()) }
        redirectErrorStream(true)
    }.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) output else null
} catch (_: Exception) {
    null
}

data class ContainerRunInputs(
    var containerClient: ContainerClient,
    var image: String,
    var remove: Boolean,
    var interactive: Boolean,
    var ports: List<String>,
    var volumes: List<String>,
    var entrypoint: String?,
    var args: List<String>,
    var environment: Map<String, String>
) : InputSet()

class ContainerRun(name: String) : Task<ContainerRunInputs, NoOutputs>(name) {
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val image = property<String>()
    var remove = true
    var interactive = false
    val ports = mutableListOf<String>()
    val volumes = mutableListOf<String>()
    var entrypoint: String? = null
    val args = mutableListOf<String>()
    val environment = mutableMapOf<String, String>()

    override val requiresElevation: Boolean get() = containerClient.directOrNull()?.rootful ?: false
    init { alwaysRun() }

    override suspend fun buildInputs(context: ExecutionContext) = ContainerRunInputs(
        containerClient.get(), image.get(), remove, interactive, ports.toList(), volumes.toList(),
        entrypoint, args.toList(), environment.toMap()
    )
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: ContainerRunInputs) = context.executeBash {
        requiresElevation = inputSet.containerClient.rootful
        command(inputSet.containerClient.command)
        args("run")
        if (inputSet.remove) args("--rm")
        if (inputSet.interactive) args("-it")
        inputSet.ports.forEach { args("-p", it) }
        inputSet.volumes.forEach { args("-v", it) }
        inputSet.entrypoint?.let { args("--entrypoint", it) }
        inputSet.environment.forEach { (key, value) -> args("-e", "$key=$value") }
        args(inputSet.image)
        args(inputSet.args)
    }
}

data class ContainerComposeInputs(
    var containerClient: ContainerClient,
    var files: List<String>,
    var services: List<String>,
    var build: Boolean,
    var wait: Boolean,
    var workingDir: String?,
    var environment: Map<String, String>
) : InputSet()

abstract class ContainerComposeTask(name: String) : Task<ContainerComposeInputs, NoOutputs>(name) {
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val files = mutableListOf<String>()
    val services = mutableListOf<String>()
    var build = false
    var wait = false
    var workingDir: String? = null
    val environment = mutableMapOf<String, String>()
    protected abstract val commandName: String
    protected open val detached: Boolean = false

    override val requiresElevation: Boolean get() = containerClient.directOrNull()?.rootful ?: false
    init { alwaysRun() }

    override suspend fun buildInputs(context: ExecutionContext) = ContainerComposeInputs(
        containerClient.get(), files.toList(), services.toList(), build, wait, workingDir,
        environment.toMap()
    )
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: ContainerComposeInputs) = context.executeBash {
        requiresElevation = inputSet.containerClient.rootful
        command(inputSet.containerClient.command)
        args("compose")
        inputSet.files.forEach { args("-f", it) }
        args(commandName)
        if (detached) args("-d")
        if (inputSet.build) args("--build")
        if (inputSet.wait) args("--wait")
        args(inputSet.services)
        inputSet.workingDir?.let { workingDir = it }
        environment.putAll(inputSet.environment)
    }
}

class ContainerComposeUp(name: String) : ContainerComposeTask(name) {
    override val commandName = "up"
    override val detached = true
}
class ContainerComposeDown(name: String) : ContainerComposeTask(name) { override val commandName = "down" }
class ContainerComposePs(name: String) : ContainerComposeTask(name) { override val commandName = "ps" }

data class ContainerImagesInputs(var containerClient: ContainerClient, var images: List<String>) : InputSet()

class ContainerRemoveImages(name: String) : Task<ContainerImagesInputs, NoOutputs>(name) {
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val images = mutableListOf<String>()
    init { alwaysRun() }
    override suspend fun buildInputs(context: ExecutionContext) = ContainerImagesInputs(containerClient.get(), images.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: ContainerImagesInputs) = context.executeBash {
        command(inputSet.containerClient.command); args("rmi", "--force"); args(inputSet.images)
    }
}

class ContainerListImages(name: String) : Task<ContainerImagesInputs, NoOutputs>(name) {
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val images = mutableListOf<String>()
    init { alwaysRun() }
    override suspend fun buildInputs(context: ExecutionContext) = ContainerImagesInputs(containerClient.get(), images.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: ContainerImagesInputs) = context.executeBash {
        command(inputSet.containerClient.command); args("images"); args(inputSet.images)
    }
}

private fun gitRevision(projectDir: Path): String? {
    return try {
        val process = ProcessBuilder(
            "git",
            "-C",
            projectDir.toString(),
            "rev-parse",
            "HEAD",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0 && output.matches(Regex("[0-9a-fA-F]{40}"))) {
            output
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
