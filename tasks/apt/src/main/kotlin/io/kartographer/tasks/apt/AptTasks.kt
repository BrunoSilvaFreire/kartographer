package io.kartographer.tasks.apt

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.dir
import io.kartographer.core.model.file
import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.executeBash
import java.io.File

open class AptPackage(name: String) : DefaultTask(name) {
    val packageName = property<String>()
    val version = property<String>().withCLI("version", "Override the package version") {
        completeWith("1.0.0", "1.1.0", "2.0.0", "latest")
    }
    val architecture = property<String> {
        set("all")
    }
    val maintainer = property<String> {
        set("Kartographer <karto@example.com>")
    }
    val description = property<String> {
        set("Kartographer package")
    }

    val outputDir = property<String> {
        set("build/apt")
    }

    val outputDeb = output("deb") {
        val pkgName = if (packageName.isPresent()) packageName.get() else name
        val pkgVersion = if (version.isPresent()) version.get() else "1.0.0"
        val arch = architecture.get()
        val outDir = outputDir.get()
        "$outDir/${pkgName}_${pkgVersion}_${arch}.deb"
    }

    private val mappings = mutableMapOf<String, String>()

    fun from(sourceDir: String, into: String) {
        inputs.dir(sourceDir)
        mappings[sourceDir] = into
    }

    fun fromFile(sourceFile: String, into: String) {
        inputs.file(sourceFile)
        mappings[sourceFile] = into
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val pkgName = if (packageName.isPresent()) packageName.get() else name
        val pkgVersion = if (version.isPresent()) version.get() else "1.0.0"
        val arch = architecture.get()
        val outDir = outputDir.get()

        val buildPath = File(context.projectDir.toFile(), "$outDir/$pkgName-$pkgVersion")
        buildPath.mkdirs()

        val debianDir = File(buildPath, "DEBIAN")
        debianDir.mkdirs()

        val controlFile = File(debianDir, "control")
        controlFile.writeText(
            """
            Package: $pkgName
            Version: $pkgVersion
            Architecture: $arch
            Maintainer: ${maintainer.get()}
            Description: ${description.get()}
        """.trimIndent() + "\n"
        )

        for ((src, dest) in mappings) {
            val srcFile = File(context.projectDir.toFile(), src)
            val destFile = File(buildPath, dest)
            destFile.parentFile.mkdirs()
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(destFile, overwrite = true)
            } else {
                srcFile.copyTo(destFile, overwrite = true)
            }
        }

        val finalDebPath = File(context.projectDir.toFile(), outputDeb.get())
        outputs.file(outputDeb.get())

        return context.executeBash {
            command("dpkg-deb")
            args("--build", buildPath.absolutePath, finalDebPath.absolutePath)
        }
    }
}

open class AptInstall(name: String) : DefaultTask(name) {
    private val directPackages = mutableListOf<String>()
    private val packageProviders = mutableListOf<io.kartographer.core.Provider<String>>()

    init {
        // requiresElevation = true will be set in the execution spec
    }

    fun install(debPath: String) {
        inputs.file(debPath)
        directPackages.add(debPath)
    }

    fun install(debProvider: io.kartographer.core.Provider<String>) {
        debProvider.producingTask?.let { dependsOn(it) }
        packageProviders.add(debProvider)
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val allPackages = directPackages + packageProviders.map { it.get() }

        if (allPackages.isEmpty()) {
            return ExecutionResult.Failure(IllegalArgumentException("No packages to install for AptInstall task '$name'"))
        }

        val pkgList = allPackages.map { File(context.projectDir.toFile(), it).absolutePath }
        return context.executeBash {
            requiresElevation = true
            command("apt-get")
            args("install", "-y")
            args(pkgList)
        }
    }
}
