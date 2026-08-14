package io.kartographer.tasks.containers

import io.kartographer.dsl.GroupSpec
import io.kartographer.tasks.DefaultTask

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
    platform: String,
    containerdNamespace: String = "k8s.io",
    requireImmutableTag: Boolean = false,
    buildArgs: Map<String, String> = emptyMap(),
): Map<String, ContainerBuild> {
    val tagGuard = if (requireImmutableTag) {
        task<DefaultTask>("validate_release_tag") {
            action { _ ->
                if (releaseTag == "latest") {
                    throw RuntimeException(
                        "Production images require an immutable RELEASE_TAG (got 'latest'). " +
                            "Set RELEASE_TAG to a unique release identifier."
                    )
                }
            }
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
                this.platform.set(platform)
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
        for (image in images) {
            val build = builds.getValue(image.name)
            task<ContainerPush>(image.name) {
                imageRef.setProvider(build.imageRef)
                // The image name already carries the registry host.
                this.registry.set("")
                containerClient.set(client)
                this.namespace.set(containerdNamespace)
                dependsOn(build)
            }
        }
    }

    return builds
}
