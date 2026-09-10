import io.kartographer.tasks.containers.*

val build = task<ContainerBuild>("docker-build") {
    imageName.set("my-app")
    containerfile.set("Dockerfile")
    context.set(".")
}

val buildVersioned = task<ContainerBuild>("docker-build-versioned") {
    imageName.set("my-app")
    containerfile.set("Dockerfile")
    context.set(".")
    version.set("1.0")
}

task<ContainerPush>("docker-push") {
    val builtImage = dependsOn(buildVersioned)
    imageRef.setProvider(builtImage.imageRef)
    registry.set("my-reg.com")
}

task<ContainerBuild>("container-build-full") {
    imageName.set("my-app")
    containerfile.set("Dockerfile")
    context.set(".")
    platforms.set(listOf("linux/amd64"))
    version.set("2.0.0")
    containerClient.set(ContainerClient.NERDCTL_ROOTFUL)
}

task<ContainerPush>("container-push-rootful") {
    imageRef.set("my-app:1.0")
    registry.set("my-reg.com")
    containerClient.set(ContainerClient.NERDCTL_ROOTFUL)
}
