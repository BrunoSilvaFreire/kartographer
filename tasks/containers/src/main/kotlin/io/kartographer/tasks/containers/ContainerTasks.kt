package io.kartographer.tasks.containers

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
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
open class ContainerBuild(name: String) : DefaultTask(name) {
    val imageName = property<String>()
    val containerfile = property<String>()
    val context = property<String> { set(".") }
    val platform = property<String>()
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val version = property<String>().withCLI("version", "Override the build version")
    val namespace = property<String>()
    val buildArgs = property<Map<String, String>>()

    override val requiresElevation: Boolean get() = containerClient.get().rootful

    // Output property (useful for wiring into Push tasks)
    val imageRef = property<String>().asOutput("image")

    init {
        // The image store is external to Kartographer's state directory and may
        // be changed or removed independently. Always rebuild when requested.
        upToDateWhen { _, _ -> false }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val clientCmd = containerClient.get().command
        val fullImageName = if (version.isPresent()) "${imageName.get()}:${version.get()}" else imageName.get()
        val configuredBuildArgs = if (buildArgs.isPresent()) buildArgs.get() else emptyMap()
        val effectiveBuildArgs = linkedMapOf<String, String>()
        if (version.isPresent() && "VERSION" !in configuredBuildArgs) {
            effectiveBuildArgs["VERSION"] = version.get()
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

        imageRef.set(fullImageName)
        return context.executeBash {
            requiresElevation = containerClient.get().rootful
            command(clientCmd)
            if (namespace.isPresent() && clientCmd.contains("nerdctl")) {
                args("--namespace", namespace.get())
            }
            args("build", "-t", fullImageName, "-f", containerfile.get())
            effectiveBuildArgs.forEach { (k, v) -> args("--build-arg", "$k=$v") }
            if (platform.isPresent()) {
                args("--platform", platform.get())
            }
            args(this@ContainerBuild.context.get())
        }
    }
}

/**
 * Pushes a container image to a registry.
 */
open class ContainerPush(name: String) : DefaultTask(name) {
    val imageRef = property<String>()
    val registry = property<String>()
    val containerClient = property<ContainerClient> { set(ContainerClient.DOCKER) }
    val namespace = property<String>()

    // Where the image actually landed, registry prefix included.
    val pushedRef = property<String>().asOutput("pushed")

    override val requiresElevation: Boolean get() = containerClient.get().rootful

    init {
        // A registry tag is external mutable state. In particular, rerunning a
        // release push must repair a missing or reduced multi-platform index.
        upToDateWhen { _, _ -> false }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val clientCmd = containerClient.get().command
        val registryStr = registry.get()
        val fullRef = if (registryStr.isEmpty()) imageRef.get() else "$registryStr/${imageRef.get()}"
        pushedRef.set(fullRef)

        if (fullRef != imageRef.get()) {
            val tagResult = context.executeBash {
                requiresElevation = containerClient.get().rootful
                command(clientCmd)
                if (namespace.isPresent() && clientCmd.contains("nerdctl")) {
                    args("--namespace", namespace.get())
                }
                args("tag", imageRef.get(), fullRef)
            }
            if (tagResult is ExecutionResult.Failure) return tagResult
        }

        return context.executeBash {
            requiresElevation = containerClient.get().rootful
            command(clientCmd)
            if (namespace.isPresent() && clientCmd.contains("nerdctl")) {
                args("--namespace", namespace.get())
            }
            args("push")
            if (clientCmd.contains("nerdctl")) {
                args("--all-platforms")
            }
            args(fullRef)
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
