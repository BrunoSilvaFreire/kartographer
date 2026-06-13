package io.kartographer.misc

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths

data class FilePaths(val paths: List<String> = emptyList()) : Iterable<Path> {
    override fun iterator(): Iterator<Path> {
        return paths.map { Paths.get(it) }.iterator()
    }
}

class FileFilter(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList()
) {
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
}
