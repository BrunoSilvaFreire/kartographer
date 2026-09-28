import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    base
}

repositories {
    mavenCentral()
    google()
}

// A single self-contained bundle: one shared lib/ and both launchers in bin/. The CLI and the
// daemon overlap heavily on their classpaths (core, gRPC, Kotlin stdlib), so a merged lib
// directory is smaller than two installations and keeps `kartographer` able to find
// `kartographer-daemon` next to itself.
val cliRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val daemonRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val bundleRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    cliRuntime(project(":cli"))
    daemonRuntime(project(":daemon"))
    bundleRuntime(project(":cli"))
    bundleRuntime(project(":daemon"))
}

val bundleDir = layout.buildDirectory.dir("bundle")
val startScriptsDir = layout.buildDirectory.dir("startScripts")

// The CLI (via Mosaic) and the daemon would otherwise resolve different coroutines versions,
// leaving one launcher pointing at a jar the shared lib/ never contains. The bundle has to
// resolve every shared dependency to exactly one version.
configurations.all {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    )
}

val kartographerStartScripts = tasks.register<CreateStartScripts>("kartographerStartScripts") {
    applicationName = "kartographer"
    mainClass = "io.kartographer.cli.MainKt"
    classpath = cliRuntime
    outputDir = startScriptsDir.get().asFile
    executableDir = "bin"
}

val daemonStartScripts = tasks.register<CreateStartScripts>("kartographerDaemonStartScripts") {
    applicationName = "kartographer-daemon"
    mainClass = "io.kartographer.daemon.MainKt"
    classpath = daemonRuntime
    outputDir = startScriptsDir.get().asFile
    executableDir = "bin"
}

val stageBundle = tasks.register<Sync>("stageBundle") {
    dependsOn(kartographerStartScripts, daemonStartScripts)
    into(bundleDir)
    from(bundleRuntime) { into("lib") }
    from(startScriptsDir) { into("bin") }
}

val distZip = tasks.register<Zip>("distZip") {
    dependsOn(stageBundle)
    archiveBaseName.set("kartographer")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(bundleDir) {
        // Conventional single top-level directory so unzipping stays tidy.
        into("kartographer-${project.version}")
        eachFile {
            // Launchers must stay executable after unzip; jars do not need it.
            if (path.contains("/bin/") || path.startsWith("bin/")) permissions { unix("0755") }
        }
    }
}

val distTar = tasks.register<Tar>("distTar") {
    dependsOn(stageBundle)
    compression = Compression.GZIP
    archiveBaseName.set("kartographer")
    archiveVersion.set(project.version.toString())
    archiveExtension.set("tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(bundleDir) {
        into("kartographer-${project.version}")
        eachFile {
            if (path.contains("/bin/") || path.startsWith("bin/")) permissions { unix("0755") }
        }
    }
}

tasks.named("assemble") {
    dependsOn(distZip, distTar)
}
