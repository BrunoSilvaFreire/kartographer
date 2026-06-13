package io.kartographer.misc

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class FilePaths(val paths: List<String> = emptyList()) : Iterable<Path> {
    override fun iterator(): Iterator<Path> {
        return paths.map { Paths.get(it) }.iterator()
    }
}

class FileFilter(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val baseDir: Path = Paths.get("")
) : Iterable<Path> {

    fun matches(path: Path): Boolean {
        if (includes.isEmpty() && excludes.isEmpty()) return true

        val fs = FileSystems.getDefault()
        val pathStr = path.toString().replace('\\', '/') // Standardize path separator

        if (includes.isNotEmpty()) {
            val matchesAnyInclude = includes.any { pattern ->
                val stdPattern = pattern.replace('\\', '/')
                val matcher = fs.getPathMatcher("glob:$stdPattern")
                matcher.matches(path) || matcher.matches(Paths.get(pathStr))
            }
            if (!matchesAnyInclude) return false
        }

        if (excludes.isNotEmpty()) {
            val matchesAnyExclude = excludes.any { pattern ->
                val stdPattern = pattern.replace('\\', '/')
                val matcher = fs.getPathMatcher("glob:$stdPattern")
                matcher.matches(path) || matcher.matches(Paths.get(pathStr))
            }
            if (matchesAnyExclude) return false
        }

        return true
    }

    override fun iterator(): Iterator<Path> {
        val resolvedBase = baseDir.toAbsolutePath().normalize()
        if (!Files.exists(resolvedBase)) return emptyList<Path>().iterator()

        val scanResult = scan(resolvedBase)
        return scanResult.paths.iterator()
    }

    private data class ScanResult(
        val paths: List<Path>,
        val fullyIncluded: Boolean
    )

    private fun scan(current: Path): ScanResult {
        val baseNorm = baseDir.toAbsolutePath().normalize()
        val rel = if (current == baseNorm) {
            Paths.get("")
        } else {
            baseNorm.relativize(current)
        }

        if (Files.isDirectory(current)) {
            val children = try {
                Files.list(current).use { stream ->
                    // Convert java.util.stream.Stream to List using collect
                    stream.toList()
                }
            } catch (e: Exception) {
                emptyList()
            }

            if (children.isEmpty()) {
                val matches = matches(rel)
                return ScanResult(
                    paths = if (matches) listOf(current) else emptyList(),
                    fullyIncluded = matches
                )
            }

            val childResults = children.map { scan(it) }
            val allChildrenFullyIncluded = childResults.all { it.fullyIncluded }
            val selfMatches = matches(rel)

            return if (selfMatches && allChildrenFullyIncluded) {
                ScanResult(listOf(current), true)
            } else {
                ScanResult(childResults.flatMap { it.paths }, false)
            }
        } else {
            val selfMatches = matches(rel)
            return ScanResult(
                paths = if (selfMatches) listOf(current) else emptyList(),
                fullyIncluded = selfMatches
            )
        }
    }
}
