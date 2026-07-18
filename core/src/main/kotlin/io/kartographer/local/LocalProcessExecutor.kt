package io.kartographer.local

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class LocalProcessExecutor(
    var redirectInput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
    var redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
    var redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
    var redirectErrorStream: Boolean = true,
    var stdoutConsumer: (String) -> Unit = { logger.info(it) },
    var stderrConsumer: (String) -> Unit = { logger.error(it) },
    var inputString: String? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(LocalProcessExecutor::class.java)
    }

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
        
        processBuilder.redirectInput(redirectInput)
        processBuilder.redirectOutput(redirectOutput)
        processBuilder.redirectError(redirectError)
        processBuilder.redirectErrorStream(redirectErrorStream)

        val process = processBuilder.start()

        // Write to stdin if configured and inputString is provided
        if (redirectInput == ProcessBuilder.Redirect.PIPE && inputString != null) {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(inputString)
            }
        }

        // Concurrently read stdout and stderr if they are configured to be piped
        coroutineScope {
            if (redirectOutput == ProcessBuilder.Redirect.PIPE) {
                launch {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutConsumer(line!!)
                        }
                    }
                }
            }

            if (!redirectErrorStream && redirectError == ProcessBuilder.Redirect.PIPE) {
                launch {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrConsumer(line!!)
                        }
                    }
                }
            }
        }

        process.waitFor()
    }
}
