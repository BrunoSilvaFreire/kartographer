package io.kartographer.local

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class LocalProcessExecutor(
    var redirectInput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
    var redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
    var redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
    var redirectErrorStream: Boolean = true,
    var stdoutConsumer: (String) -> Unit = { logger.info(it) },
    var stderrConsumer: (String) -> Unit = { logger.error(it) },
    var inputString: String? = null,
    var stdinChannel: kotlinx.coroutines.channels.ReceiveChannel<String>? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(LocalProcessExecutor::class.java)
        private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()

        var defaultGracePeriodMillis: Long = 2500L

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                activeProcesses.forEach { process ->
                    if (process.isAlive) {
                        runCatching {
                            process.descendants().forEach { it.destroyForcibly() }
                            process.destroyForcibly()
                        }
                    }
                }
            })
        }

        fun terminateProcessTree(process: Process, signal: Int = 2, gracePeriodMillis: Long = defaultGracePeriodMillis) {
            if (!process.isAlive) return
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                runCatching { process.descendants().forEach { it.destroy() } }
                runCatching { process.destroy() }
            } else {
                // POSIX: send signal (default SIGINT = 2) to deepest descendants first, then the root process
                val descendants = runCatching { process.descendants().toList().reversed() }.getOrDefault(emptyList())
                for (handle in descendants) {
                    if (handle.isAlive) {
                        sendPosixSignal(handle.pid(), signal)
                    }
                }
                if (process.isAlive) {
                    sendPosixSignal(process.toHandle().pid(), signal)
                }
            }

            // Schedule escalation to SIGKILL if process does not exit within gracePeriodMillis
            Thread({
                val deadline = System.currentTimeMillis() + gracePeriodMillis
                while (System.currentTimeMillis() < deadline && process.isAlive) {
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (process.isAlive) {
                    runCatching {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                    }
                }
            }, "karto-process-escalation").apply { isDaemon = true }.start()
        }

        private fun sendPosixSignal(pid: Long, signal: Int) {
            val sent = runCatching {
                val killProc = ProcessBuilder("kill", "-$signal", pid.toString()).start()
                val exited = killProc.waitFor(500, TimeUnit.MILLISECONDS)
                exited && killProc.exitValue() == 0
            }.getOrDefault(false)

            if (!sent && signal != 9) {
                // Fallback to ProcessHandle destroy if kill command failed
                ProcessHandle.of(pid).ifPresent { it.destroy() }
            }
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
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
        activeProcesses.add(process)

        val job = coroutineContext.job
        val cancellationHandler = job.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) {
                terminateProcessTree(process, signal = 2)
                runCatching { process.outputStream.close() }
            }
        }

        try {
            // Write to stdin if configured and inputString is provided
            if (redirectInput == ProcessBuilder.Redirect.PIPE && inputString != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(inputString)
                    writer.flush()
                }
            }

            // Concurrently read stdout and stderr, and forward stdin channel if piped
            coroutineScope {
                val stdinJob = stdinChannel?.let { channel ->
                    if (redirectInput == ProcessBuilder.Redirect.PIPE) {
                        launch(Dispatchers.IO) {
                            try {
                                for (data in channel) {
                                    if (!process.isAlive) break
                                    process.outputStream.write(data.toByteArray())
                                    process.outputStream.flush()
                                }
                            } catch (_: Exception) {
                                // Channel closed, process exited, or coroutine cancelled
                            }
                        }
                    } else null
                }

                val stdoutJob = if (redirectOutput == ProcessBuilder.Redirect.PIPE) {
                    launch(Dispatchers.IO) {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                stdoutConsumer(line!!)
                            }
                        }
                    }
                } else null

                val stderrJob = if (!redirectErrorStream && redirectError == ProcessBuilder.Redirect.PIPE) {
                    launch(Dispatchers.IO) {
                        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                stderrConsumer(line!!)
                            }
                        }
                    }
                } else null

                val exitCodeJob = launch(Dispatchers.IO) {
                    process.waitFor()
                }

                exitCodeJob.join()
                stdoutJob?.join()
                stderrJob?.join()
                stdinJob?.cancel()
            }

            process.exitValue()
        } finally {
            cancellationHandler.dispose()
            activeProcesses.remove(process)
            if (process.isAlive) {
                runCatching {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        }
    }
}
