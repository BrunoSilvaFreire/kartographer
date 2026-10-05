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
// JNA is shared too: the CLI needs it transitively through Mordant's terminal detection, and
// the daemon now declares it for the native task-log sinks. Without pinning, the CLI start
// script references the older transitive jar while lib/ only holds the daemon's version.
configurations.all {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        "net.java.dev.jna:jna:5.17.0",
        "net.java.dev.jna:jna-platform:5.17.0"
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

// -------------------------------------------------------------------------------------------
// Native installers via jpackage. jpackage creates a self-contained app image with its own
// trimmed runtime, so a .deb or .msi needs no system Java. It drives the launcher classpath
// from the main jar's manifest Class-Path, so each launcher gets a small generated "pointer"
// jar listing exactly the jars it needs (all of which sit beside it in the app image).
// -------------------------------------------------------------------------------------------

val isWindowsHost = System.getProperty("os.name").lowercase().contains("win")
val jpackageVersion = providers.provider { project.version.toString().substringBefore('-') }
val jpackageInput = layout.buildDirectory.dir("jpackage/input")
val daemonLauncherFile = layout.buildDirectory.file("jpackage/launchers/daemon.properties")

val cliClassPath = providers.provider { cliRuntime.files.joinToString(" ") { it.name } }
val daemonClassPath = providers.provider { daemonRuntime.files.joinToString(" ") { it.name } }

val cliAppJar = tasks.register<Jar>("cliAppJar") {
    archiveFileName.set("kartographer-cli.jar")
    destinationDirectory.set(layout.buildDirectory.dir("jpackage/app"))
    manifest {
        attributes(
            "Main-Class" to "io.kartographer.cli.MainKt",
            "Class-Path" to cliClassPath,
        )
    }
}

val daemonAppJar = tasks.register<Jar>("daemonAppJar") {
    archiveFileName.set("kartographer-daemon-app.jar")
    destinationDirectory.set(layout.buildDirectory.dir("jpackage/app"))
    manifest {
        attributes(
            "Main-Class" to "io.kartographer.daemon.MainKt",
            "Class-Path" to daemonClassPath,
        )
    }
}

val stageJpackageInput = tasks.register<Sync>("stageJpackageInput") {
    dependsOn(cliAppJar, daemonAppJar)
    into(jpackageInput)
    from(bundleRuntime)
    from(cliAppJar)
    from(daemonAppJar)
}

val writeDaemonLauncher = tasks.register("writeDaemonLauncher") {
    val output = daemonLauncherFile.get().asFile
    outputs.file(daemonLauncherFile)
    doLast {
        output.parentFile.mkdirs()
        output.writeText(
            "main-jar=kartographer-daemon-app.jar\n" +
                "main-class=io.kartographer.daemon.MainKt\n"
        )
    }
}

val packageDeb = tasks.register<Exec>("packageDeb") {
    group = "distribution"
    description = "Build a self-contained Debian package (Linux only)."
    onlyIf { !isWindowsHost }
    dependsOn(stageJpackageInput, writeDaemonLauncher)
    val dest = layout.buildDirectory.dir("distributions").get().asFile
    doFirst { dest.mkdirs() }
    commandLine(
        "jpackage",
        "--type", "deb",
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", "kartographer-cli.jar",
        "--main-class", "io.kartographer.cli.MainKt",
        "--add-launcher", "kartographer-daemon=${daemonLauncherFile.get().asFile.absolutePath}",
        "--name", "kartographer",
        "--app-version", jpackageVersion.get(),
        "--linux-package-name", "kartographer",
        "--linux-shortcut",
        "--dest", dest.absolutePath,
    )
}

val packageRpm = tasks.register<Exec>("packageRpm") {
    group = "distribution"
    description = "Build a self-contained RPM package for Fedora/RHEL (Linux only; needs jpackage + rpm-build)."
    onlyIf { !isWindowsHost }
    dependsOn(stageJpackageInput, writeDaemonLauncher)
    val dest = layout.buildDirectory.dir("distributions").get().asFile
    doFirst { dest.mkdirs() }
    commandLine(
        "jpackage",
        "--type", "rpm",
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", "kartographer-cli.jar",
        "--main-class", "io.kartographer.cli.MainKt",
        "--add-launcher", "kartographer-daemon=${daemonLauncherFile.get().asFile.absolutePath}",
        "--name", "kartographer",
        "--app-version", jpackageVersion.get(),
        "--linux-package-name", "kartographer",
        "--linux-shortcut",
        "--dest", dest.absolutePath,
    )
}

val packageMsi = tasks.register<Exec>("packageMsi") {
    group = "distribution"
    description = "Build a self-contained Windows MSI installer (Windows only)."
    onlyIf { isWindowsHost }
    dependsOn(stageJpackageInput, writeDaemonLauncher)
    val dest = layout.buildDirectory.dir("distributions").get().asFile
    doFirst { dest.mkdirs() }
    commandLine(
        "jpackage",
        "--type", "msi",
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", "kartographer-cli.jar",
        "--main-class", "io.kartographer.cli.MainKt",
        "--add-launcher", "kartographer-daemon=${daemonLauncherFile.get().asFile.absolutePath}",
        "--name", "kartographer",
        "--app-version", jpackageVersion.get(),
        "--win-console",
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
        "--dest", dest.absolutePath,
    )
}
