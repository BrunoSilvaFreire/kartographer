package io.kartographer.tasks.apt

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.dir
import io.kartographer.core.model.file
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash
import java.io.File

data class AptPackageInputs(
    var packageName: String,
    var version: String,
    var architecture: String,
    var maintainer: String,
    var description: String,
    var outputDir: String,
    var mappings: Map<String, String>
) : InputSet()

class AptPackageOutputs : OutputSet() {
    val outputDeb = output<String>("deb")
}

open class AptPackage(name: String) : Task<AptPackageInputs, AptPackageOutputs>(name) {
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

    private val mappings = mutableMapOf<String, String>()

    fun from(sourceDir: String, into: String) {
        inputs.dir(sourceDir)
        mappings[sourceDir] = into
    }

    fun fromFile(sourceFile: String, into: String) {
        inputs.file(sourceFile)
        mappings[sourceFile] = into
    }

    override suspend fun buildInputs(context: ExecutionContext) = AptPackageInputs(
        packageName.orNull() ?: name,
        version.orNull() ?: "1.0.0",
        architecture.get(),
        maintainer.get(),
        description.get(),
        outputDir.get(),
        mappings.toMap()
    )

    override fun buildOutputs() = AptPackageOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: AptPackageInputs): ExecutionResult<AptPackageOutputs> {
        val pkgName = inputSet.packageName
        val pkgVersion = inputSet.version
        val arch = inputSet.architecture
        val outDir = inputSet.outputDir

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
            Maintainer: ${inputSet.maintainer}
            Description: ${inputSet.description}
        """.trimIndent() + "\n"
        )

        for ((src, dest) in inputSet.mappings) {
            val srcFile = File(context.projectDir.toFile(), src)
            val destFile = File(buildPath, dest)
            destFile.parentFile.mkdirs()
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(destFile, overwrite = true)
            } else {
                srcFile.copyTo(destFile, overwrite = true)
            }
        }

        val outputDeb = "$outDir/${pkgName}_${pkgVersion}_${arch}.deb"
        val finalDebPath = File(context.projectDir.toFile(), outputDeb)

        val result = context.executeBash {
            command("dpkg-deb")
            args("--build", buildPath.absolutePath, finalDebPath.absolutePath)
        }
        return when (result) {
            is ExecutionResult.Failure -> failure(result.error, result.exitCode)
            else -> success { this.outputDeb.set(outputDeb) }
        }
    }
}

data class AptInstallInputs(var packages: List<String>) : InputSet()

open class AptInstall(name: String) : Task<AptInstallInputs, NoOutputs>(name) {
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
        (debProvider as? io.kartographer.core.TaskBoundProvider)?.producingTask?.let { dependsOn(it) }
        packageProviders.add(debProvider)
    }

    override suspend fun buildInputs(context: ExecutionContext) = AptInstallInputs(
        directPackages.toList() + packageProviders.map { it.get() }
    )
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: AptInstallInputs): ExecutionResult<NoOutputs> {
        val allPackages = inputSet.packages

        if (allPackages.isEmpty()) {
            return failure(IllegalArgumentException("No packages to install for AptInstall task '$name'"))
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
