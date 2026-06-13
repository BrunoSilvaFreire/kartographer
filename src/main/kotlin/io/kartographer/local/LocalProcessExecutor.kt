package io.kartographer.local

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalProcessExecutor {
    suspend fun execute(
        command: String,
        workingDir: String,
        environment: Map<String, String>
    ): Int = withContext(Dispatchers.IO) {
        val os = System.getProperty("os.name").lowercase()
        val processBuilder = if (os.contains("win")) {
            ProcessBuilder("cmd.exe", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }

        processBuilder.directory(File(workingDir))
        processBuilder.environment().clear()
        processBuilder.environment().putAll(environment)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()

        // Stream logs directly to stdout
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                println(line)
            }
        }

        process.waitFor()
    }
}
