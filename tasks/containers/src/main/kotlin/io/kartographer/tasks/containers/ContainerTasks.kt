package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash
import java.time.Instant
import java.nio.file.Path

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
        // The image store is external to Kartographer's state directory and may
        // be changed or removed independently. Always rebuild when requested.
        alwaysRun()
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
            else -> success { imageRef.set(fullImageName) }
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
        // A registry tag is external mutable state. In particular, rerunning a
        // release push must repair a missing or reduced multi-platform index.
        alwaysRun()
    }

    override suspend fun buildInputs(context: ExecutionContext) = ContainerPushInputs(
        imageRef.get(), registry.get(), containerClient.get(), namespace.orNull()
    )

    override fun buildOutputs() = ContainerPushOutputs()

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
