package io.kartographer.tasks

import io.kartographer.core.model.Task
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.misc.FileFilter
import io.kartographer.misc.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

data class CopyInputs(
    var sources: List<String>,
    var destination: String?,
    var includes: List<String>,
    var excludes: List<String>
) : InputSet()

open class Copy(name: String) : Task<CopyInputs, NoOutputs>(name) {
    var sources: FilePaths = FilePaths(emptyList())
    var destination: String? = null
    var filter: FileFilter = FileFilter()

    override suspend fun buildInputs(context: ExecutionContext) = CopyInputs(
        sources.paths.toList(), destination, filter.includes.toList(), filter.excludes.toList()
    )

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: CopyInputs): ExecutionResult<NoOutputs> {
        val dest = inputSet.destination?.let { context.projectDir.resolve(it) }
            ?: return failure(IllegalArgumentException("Destination directory is not specified."))
        val filter = FileFilter(inputSet.includes, inputSet.excludes)

        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(dest)
            }
            for (sourceStr in inputSet.sources) {
                val sourcePath = context.projectDir.resolve(sourceStr)
                if (!Files.exists(sourcePath)) {
                    return failure(IllegalArgumentException("Source path '$sourceStr' does not exist."))
                }

                if (Files.isDirectory(sourcePath)) {
                    Files.walk(sourcePath).forEach { subPath ->
                        val rel = sourcePath.relativize(subPath)
                        if (filter.matches(rel)) {
                            val target = dest.resolve(rel)
                            if (Files.isDirectory(subPath)) {
                                Files.createDirectories(target)
                            } else {
                                Files.createDirectories(target.parent)
                                Files.copy(subPath, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                } else {
                    val fileNamePath = sourcePath.fileName
                    if (filter.matches(fileNamePath)) {
                        val target = dest.resolve(fileNamePath)
                        Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            return success()
        } catch (t: Throwable) {
            return failure(t)
        }
    }
}

data class SyncInputs(
    var sources: List<String>,
    var destination: String?,
    var includes: List<String>,
    var excludes: List<String>
) : InputSet()

open class Sync(name: String) : Task<SyncInputs, NoOutputs>(name) {
    var sources: FilePaths = FilePaths(emptyList())
    var destination: String? = null
    var filter: FileFilter = FileFilter()

    override suspend fun buildInputs(context: ExecutionContext) = SyncInputs(
        sources.paths.toList(), destination, filter.includes.toList(), filter.excludes.toList()
    )

    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: SyncInputs): ExecutionResult<NoOutputs> {
        val dest = inputSet.destination?.let { context.projectDir.resolve(it) }
            ?: return failure(IllegalArgumentException("Destination directory is not specified."))
        val filter = FileFilter(inputSet.includes, inputSet.excludes)

        try {
            Files.createDirectories(dest)
            val copiedTargets = mutableSetOf<Path>()

            for (sourceStr in inputSet.sources) {
                val sourcePath = context.projectDir.resolve(sourceStr)
                if (!Files.exists(sourcePath)) {
                    return failure(IllegalArgumentException("Source path '$sourceStr' does not exist."))
                }

                if (Files.isDirectory(sourcePath)) {
                    Files.walk(sourcePath).forEach { subPath ->
                        val rel = sourcePath.relativize(subPath)
                        if (filter.matches(rel)) {
                            val target = dest.resolve(rel)
                            copiedTargets.add(target)
                            if (Files.isDirectory(subPath)) {
                                Files.createDirectories(target)
                            } else {
                                Files.createDirectories(target.parent)
                                Files.copy(subPath, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                } else {
                    val fileNamePath = sourcePath.fileName
                    if (filter.matches(fileNamePath)) {
                        val target = dest.resolve(fileNamePath)
                        copiedTargets.add(target)
                        Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }

            // Sync: remove files in destination that were not copied
            if (Files.exists(dest)) {
                Files.walk(dest)
                    .sorted(Comparator.reverseOrder())
                    .forEach { path ->
                        if (path != dest && path !in copiedTargets) {
                            Files.delete(path)
                        }
                    }
            }

            return success()
        } catch (t: Throwable) {
            return failure(t)
        }
    }
}

data class MoveInputs(var sources: List<String>, var destination: String?) : InputSet()

open class Move(name: String) : Task<MoveInputs, NoOutputs>(name) {
    var sources: FilePaths = FilePaths(emptyList())
    var destination: String? = null

    override suspend fun buildInputs(context: ExecutionContext) = MoveInputs(sources.paths.toList(), destination)
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: MoveInputs): ExecutionResult<NoOutputs> {
        val dest = inputSet.destination?.let { context.projectDir.resolve(it) }
            ?: return failure(IllegalArgumentException("Destination directory is not specified."))

        try {
            Files.createDirectories(dest)
            for (sourceStr in inputSet.sources) {
                val sourcePath = context.projectDir.resolve(sourceStr)
                if (!Files.exists(sourcePath)) {
                    return failure(IllegalArgumentException("Source path '$sourceStr' does not exist."))
                }

                if (Files.isDirectory(sourcePath)) {
                    Files.walk(sourcePath).forEach { subPath ->
                        val rel = sourcePath.relativize(subPath)
                        val target = dest.resolve(rel)
                        if (Files.isDirectory(subPath)) {
                            Files.createDirectories(target)
                        } else {
                            Files.createDirectories(target.parent)
                            Files.move(subPath, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    // Delete empty source directories recursively
                    Files.walk(sourcePath)
                        .sorted(Comparator.reverseOrder())
                        .forEach { Files.deleteIfExists(it) }
                } else {
                    val target = dest.resolve(sourcePath.fileName)
                    Files.move(sourcePath, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return success()
        } catch (t: Throwable) {
            return failure(t)
        }
    }
}

data class DeleteInputs(var files: List<String>) : InputSet()

open class Delete(name: String) : Task<DeleteInputs, NoOutputs>(name) {
    var files: FilePaths = FilePaths(emptyList())

    override suspend fun buildInputs(context: ExecutionContext) = DeleteInputs(files.paths.toList())
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: DeleteInputs): ExecutionResult<NoOutputs> {
        try {
            for (fileStr in inputSet.files) {
                val path = context.projectDir.resolve(fileStr)
                if (Files.exists(path)) {
                    if (Files.isDirectory(path)) {
                        Files.walk(path)
                            .sorted(Comparator.reverseOrder())
                            .forEach { Files.delete(it) }
                    } else {
                        Files.delete(path)
                    }
                }
            }
            return success()
        } catch (t: Throwable) {
            return failure(t)
        }
    }
}
