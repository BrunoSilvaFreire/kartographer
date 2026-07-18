package io.kartographer.integration

import java.io.File

fun resolveScriptPath(path: String): String {
    val file = File(path)
    if (file.exists()) return file.absolutePath
    val parentFile = File("../", path)
    if (parentFile.exists()) return parentFile.absolutePath
    throw IllegalArgumentException("Script file not found: $path (also tried: ${parentFile.absolutePath})")
}
