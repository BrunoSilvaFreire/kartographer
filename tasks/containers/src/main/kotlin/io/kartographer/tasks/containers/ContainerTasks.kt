package io.kartographer.tasks.containers

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeBash

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
    val imageRef = property<String>()

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        
        val clientCmd = containerClient.get().command
        val fullImageName = if (version.isPresent()) "${imageName.get()}:${version.get()}" else imageName.get()
        
        imageRef.set(fullImageName)
        return context.executeBash {
            requiresElevation = containerClient.get().rootful
            command(clientCmd)
            if (namespace.isPresent() && clientCmd.contains("nerdctl")) {
                args("--namespace", namespace.get())
            }
            args("build", "-t", fullImageName, "-f", containerfile.get())
            if (buildArgs.isPresent()) {
                buildArgs.get().forEach { (k, v) -> args("--build-arg", "$k=$v") }
            }
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

    override val requiresElevation: Boolean get() = containerClient.get().rootful

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        
        val clientCmd = containerClient.get().command
        val registryStr = registry.get()
        val fullRef = if (registryStr.isEmpty()) imageRef.get() else "$registryStr/${imageRef.get()}"
        
        val res1 = context.executeBash {
            requiresElevation = containerClient.get().rootful
            command(clientCmd)
            if (namespace.isPresent() && clientCmd.contains("nerdctl")) {
                args("--namespace", namespace.get())
            }
            args("tag", imageRef.get(), fullRef)
        }
        if (res1 is ExecutionResult.Failure) return res1

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
