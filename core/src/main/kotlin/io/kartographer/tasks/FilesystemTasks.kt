package io.kartographer.tasks

import io.kartographer.core.model.Task
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.misc.FileFilter
import io.kartographer.misc.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

open class Copy(name: String) : Task(name) {
    var sources: FilePaths = FilePaths(emptyList())
    var destination: String? = null
    var filter: FileFilter = FileFilter()

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val dest = destination?.let { context.projectDir.resolve(it) }
            ?: return ExecutionResult.Failure(IllegalArgumentException("Destination directory is not specified."))

        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(dest)
            }
            for (sourceStr in sources.paths) {
                val sourcePath = context.projectDir.resolve(sourceStr)
                if (!Files.exists(sourcePath)) {
                    return ExecutionResult.Failure(IllegalArgumentException("Source path '$sourceStr' does not exist."))
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
            return ExecutionResult.Success
        } catch (t: Throwable) {
            return ExecutionResult.Failure(t)
        }
    }
}

open class Sync(name: String) : Task(name) {
    var sources: FilePaths = FilePaths(emptyList())
    var destination: String? = null
    var filter: FileFilter = FileFilter()

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val dest = destination?.let { context.projectDir.resolve(it) }
            ?: return ExecutionResult.Failure(IllegalArgumentException("Destination directory is not specified."))

        try {
            Files.createDirectories(dest)
            val copiedTargets = mutableSetOf<Path>()

            for (sourceStr in sources.paths) {
                val sourcePath = context.projectDir.resolve(sourceStr)
                if (!Files.exists(sourcePath)) {
                    return ExecutionResult.Failure(IllegalArgumentException("Source path '$sourceStr' does not exist."))
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

            return ExecutionResult.Success
        } catch (t: Throwable) {
            return ExecutionResult.Failure(t)
        }
    }
}

open class Move(name: String) : Task(name) {
    var sources: FilePaths = FilePaths(emptyList())
    var destination: String? = null

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val dest = destination?.let { context.projectDir.resolve(it) }
            ?: return ExecutionResult.Failure(IllegalArgumentException("Destination directory is not specified."))

        try {
            Files.createDirectories(dest)
            for (sourceStr in sources.paths) {
                val sourcePath = context.projectDir.resolve(sourceStr)
                if (!Files.exists(sourcePath)) {
                    return ExecutionResult.Failure(IllegalArgumentException("Source path '$sourceStr' does not exist."))
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
            return ExecutionResult.Success
        } catch (t: Throwable) {
            return ExecutionResult.Failure(t)
        }
    }
}

open class Delete(name: String) : Task(name) {
    var files: FilePaths = FilePaths(emptyList())

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        try {
            for (fileStr in files.paths) {
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
            return ExecutionResult.Success
        } catch (t: Throwable) {
            return ExecutionResult.Failure(t)
        }
    }
}