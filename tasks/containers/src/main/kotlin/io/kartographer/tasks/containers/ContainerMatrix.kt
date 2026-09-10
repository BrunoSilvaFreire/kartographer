package io.kartographer.tasks.containers

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.dsl.GroupSpec

data class ImmutableReleaseTagInputs(var version: String) : InputSet()

class ImmutableReleaseTag(name: String) : Task<ImmutableReleaseTagInputs, NoOutputs>(name) {
    val version = property<String>().withCLI("version", "Override the image version")

    override suspend fun buildInputs(context: ExecutionContext) = ImmutableReleaseTagInputs(version.get())
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(
        context: ExecutionContext,
        inputSet: ImmutableReleaseTagInputs,
    ): ExecutionResult<NoOutputs> = if (inputSet.version == "latest") {
        failure(
            IllegalArgumentException(
                "Production images require an immutable version (got 'latest'). " +
                    "Set --version to a unique release identifier."
            )
        )
    } else {
        success()
    }
}

/**
 * One image in a project's container set.
 *
 * [deps] names other images in the same set whose build must happen first — a Containerfile
 * that starts `FROM $PROJECT_NAMESPACE/<other>:$PROJECT_VERSION` depends on `<other>`.
 */
data class ContainerImage(
    val name: String,
    val containerfile: String,
    val context: String = ".",
    val deps: List<String> = emptyList(),
    /** Registry image name, when it differs from the task name. */
    val imageName: String = name,
)

/**
 * Registers the `build/<image>` and `push/<image>` pair for every image in [images], wiring
 * [ContainerImage.deps] into build ordering and each push onto its own build.
 *
 * Every Cityscape repo that ships images had its own copy of this loop; they differed only in
 * which fields their image records happened to carry.
 *
 * With [requireImmutableTag], a `validate_release_tag` guard is registered and every build
 * depends on it, so a production run refuses the mutable `latest` default before it starts
 * pushing anything.
 *
 * Returns the builds by image name so a caller can attach extra dependencies — an image built
 * from a compiled artifact needs to depend on whatever produces it.
 */
fun GroupSpec.containerMatrix(
    images: List<ContainerImage>,
    registry: String,
    namespace: String,
    releaseTag: String,
    client: ContainerClient,
    platforms: List<String>,
    containerdNamespace: String = "k8s.io",
    requireImmutableTag: Boolean = false,
    buildArgs: Map<String, String> = emptyMap(),
): Map<String, ContainerBuild> {
    val tagGuard = if (requireImmutableTag) {
        task<ImmutableReleaseTag>("validate_release_tag") {
            version.set(releaseTag)
        }
    } else {
        null
    }

    val builds = LinkedHashMap<String, ContainerBuild>()

    group("build") {
        for (image in images) {
            builds[image.name] = task<ContainerBuild>(image.name) {
                imageName.set("$registry/$namespace/${image.imageName}")
                containerfile.set(image.containerfile)
                context.set(image.context)
                containerClient.set(client)
                this.platforms.set(platforms)
                this.namespace.set(containerdNamespace)
                version.set(releaseTag)
                if (buildArgs.isNotEmpty()) {
                    this.buildArgs.set(buildArgs)
                }
                tagGuard?.let { dependsOn(it) }
                // Declared before use: an image may only depend on one already registered,
                // which is what keeps the set acyclic.
                for (dep in image.deps) {
                    val upstream = builds[dep]
                        ?: throw IllegalArgumentException(
                            "Image '${image.name}' depends on '$dep', which is not defined before it."
                        )
                    dependsOn(upstream)
                }
            }
        }
    }

    group("push") {
        // The group itself exposes --version so `push` can be run as a whole at
        // one release tag; the value is applied to each member push (and, through
        // its parametrized dependency, each build) by the CLI's property plumbing.
        property<String> { withCLI("version", "Override the image version to build and push") }
        for (image in images) {
            val build = builds.getValue(image.name)
            task<ContainerPush>(image.name) {
                // A parametrized dependency: `push/<image> --version=X` must
                // build (and therefore tag) the image as X, not whatever
                // RELEASE_TAG the build was registered with. The configured
                // dependency pins the build invocation to this push's version.
                val builtImage = dependsOn(build) { inputs ->
                    version.directOrNull()?.let { inputs.version = it }
                }
                imageRef.setProvider(builtImage.imageRef)
                // The image name already carries the registry host.
                this.registry.set("")
                containerClient.set(client)
                this.namespace.set(containerdNamespace)
            }
        }
    }

    return builds
}
