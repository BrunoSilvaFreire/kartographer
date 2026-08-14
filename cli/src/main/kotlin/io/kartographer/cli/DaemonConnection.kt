package io.kartographer.cli

import io.kartographer.core.daemon.BuildStamp
import io.kartographer.core.daemon.DaemonEndpoint
import io.kartographer.core.daemon.LocalProtocol
import io.kartographer.daemon.proto.CompleteRequest
import io.kartographer.daemon.proto.CompleteResponse
import io.kartographer.daemon.proto.DescribeProjectRequest
import io.kartographer.daemon.proto.DescribeProjectResponse
import io.kartographer.daemon.proto.LocalResponse
import io.kartographer.daemon.proto.RunClientMessage
import io.kartographer.daemon.proto.RunServerMessage
import io.kartographer.daemon.proto.daemonInfoRequest
import io.kartographer.daemon.proto.localRequest
import io.kartographer.daemon.proto.shutdownRequest
import java.io.Closeable
import java.io.File

/**
 * The CLI's link to the local daemon.
 *
 * Every command goes through here: the CLI no longer loads pipelines or executes tasks, which
 * is what lets it ship without the Kotlin compiler and the task modules. It talks the framed
 * local protocol rather than gRPC because gRPC's client stack costs roughly ten times more to
 * initialise than the daemon needs to answer.
 */
class DaemonConnection private constructor(
    private val client: LocalProtocol.Client
) : Closeable {

    /** Build stamp reported during the liveness probe, reused by the staleness check. */
    private var reportedVersion: String = ""

    override fun close() = client.close()

    class DaemonUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun describeProject(request: DescribeProjectRequest): DescribeProjectResponse =
        client.call(localRequest { describeProject = request }).unwrap { describeProject }

    fun complete(request: CompleteRequest): CompleteResponse =
        client.call(localRequest { complete = request }).unwrap { complete }

    /**
     * Starts a run and hands back a handle for the conversation: events come back while
     * prompt answers and a possible cancel go out.
     */
    fun startRun(start: RunClientMessage): RunStream {
        client.send(localRequest { run = start })
        return RunStream(client)
    }

    class RunStream(private val client: LocalProtocol.Client) {
        fun send(message: RunClientMessage) = client.send(localRequest { run = message })

        /** Null when the daemon closed the stream. */
        fun receive(): RunServerMessage? {
            val response = client.receive() ?: return null
            return when (response.responseCase) {
                LocalResponse.ResponseCase.RUN -> response.run
                LocalResponse.ResponseCase.ERROR ->
                    throw DaemonUnavailable("Daemon error: ${response.error.message}")
                else -> null
            }
        }
    }

    private inline fun <T> LocalResponse.unwrap(select: LocalResponse.() -> T): T {
        if (responseCase == LocalResponse.ResponseCase.ERROR) {
            throw DaemonUnavailable("Daemon error: ${error.message}")
        }
        return select()
    }

    private fun requestShutdown() {
        runCatching { client.call(localRequest { shutdown = shutdownRequest { } }) }
    }

    /**
     * Whether the running daemon matches the installed distribution.
     *
     * Compares jar signatures rather than version strings — every development build is
     * `0.1.0-SNAPSHOT`, so a version would never detect a rebuild. When the installed
     * distribution cannot be located there is nothing to compare against, and the running
     * daemon is accepted rather than needlessly restarted.
     */
    private fun isCurrent(): Boolean {
        if (reportedVersion.isBlank()) return true
        val installed = installedDaemonLibDir()?.let { BuildStamp.ofLibDirectory(it) } ?: return true
        return installed == reportedVersion
    }

    companion object {
        /** Env override for the daemon launcher, mirroring [DaemonEndpoint.SOCKET_ENV]. */
        const val DAEMON_BIN_ENV = "KARTOGRAPHER_DAEMON_BIN"

        private const val STARTUP_TIMEOUT_MILLIS = 20_000L
        private const val POLL_INTERVAL_MILLIS = 25L

        /**
         * Connects to the daemon, starting one if needed.
         *
         * @param autoStart when false, an absent daemon is reported rather than launched.
         * Completion passes false: spawning a JVM from inside a tab-completion hook would
         * freeze the user's shell for seconds.
         */
        fun connect(autoStart: Boolean = true): DaemonConnection {
            val socket = DaemonEndpoint.socketPath()
            Timing.mark("daemon: socket resolved")

            val existing = if (socket.exists()) tryConnect(socket) else null
            Timing.mark("daemon: probed")

            if (existing != null) {
                if (!autoStart || existing.isCurrent()) return existing
                // The installation was rebuilt since this daemon started, so it is holding
                // task classes and compiled pipelines from the previous build.
                existing.requestShutdown()
                existing.close()
                waitForSocketToDisappear(socket)
            }

            if (!autoStart) {
                throw DaemonUnavailable("No Kartographer daemon is listening on ${socket.absolutePath}")
            }

            spawnDaemon()
            return awaitDaemon(socket)
        }

        private fun tryConnect(socket: File): DaemonConnection? = try {
            val connection = DaemonConnection(LocalProtocol.Client.connect(socket.toPath()))
            // Doubles as the liveness probe: a socket file left behind by a killed daemon
            // looks alive until something actually dials it. The version is kept so the
            // staleness check does not cost a second round trip.
            connection.reportedVersion = connection
                .client.call(localRequest { daemonInfo = daemonInfoRequest { } })
                .daemonInfo.version
            connection
        } catch (e: Exception) {
            null
        }

        private fun awaitDaemon(socket: File): DaemonConnection {
            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (socket.exists()) {
                    tryConnect(socket)?.let { return it }
                }
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            throw DaemonUnavailable(
                "Kartographer daemon did not start within ${STARTUP_TIMEOUT_MILLIS / 1000}s. " +
                    "Check its log at ${daemonLogFile().absolutePath}"
            )
        }

        private fun waitForSocketToDisappear(socket: File) {
            val deadline = System.currentTimeMillis() + 5_000
            while (socket.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        }

        private fun spawnDaemon() {
            val binary = findDaemonBinary()
                ?: throw DaemonUnavailable(
                    "Could not find the 'kartographer-daemon' executable. Put it on your PATH " +
                        "(./bootstrap.sh does this) or set $DAEMON_BIN_ENV."
                )

            val log = daemonLogFile()
            log.parentFile?.mkdirs()

            try {
                ProcessBuilder(binary.absolutePath)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    .redirectErrorStream(true)
                    // Detached: the daemon has to outlive this CLI invocation, and inheriting
                    // our stdio would let it scribble over the user's terminal later.
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .start()
            } catch (e: Exception) {
                throw DaemonUnavailable("Failed to start the Kartographer daemon: ${e.localizedMessage}", e)
            }
        }

        private fun findDaemonBinary(): File? {
            System.getenv(DAEMON_BIN_ENV)?.takeIf { it.isNotBlank() }?.let { path ->
                return File(path).takeIf { it.canExecute() }
            }

            val fromPath = (System.getenv("PATH") ?: "")
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, "kartographer-daemon") }
                .firstOrNull { it.canExecute() }
            if (fromPath != null) return fromPath

            return daemonBinaryFromSiblingInstall()
        }

        /**
         * Falls back to the repository layout `./gradlew installDist` produces, so the daemon
         * is reachable from a build tree that was never bootstrapped onto PATH.
         */
        private fun daemonBinaryFromSiblingInstall(): File? {
            val cliInstallDir = cliInstallDir() ?: return null
            val repoRoot = cliInstallDir.parentFile?.parentFile?.parentFile?.parentFile ?: return null
            return File(repoRoot, "daemon/build/install/daemon/bin/daemon").takeIf { it.canExecute() }
        }

        /** `<...>/cli/build/install/cli`, derived from the jar this class was loaded from. */
        private fun cliInstallDir(): File? {
            val location = DaemonConnection::class.java.protectionDomain?.codeSource?.location ?: return null
            val jar = runCatching { File(location.toURI()) }.getOrNull() ?: return null
            // <install>/lib/cli-<version>.jar -> <install>
            return jar.parentFile?.parentFile
        }

        /** The `lib/` directory of the daemon distribution this CLI would launch. */
        private fun installedDaemonLibDir(): File? =
            findDaemonBinary()?.let { binary ->
                val real = runCatching { binary.toPath().toRealPath().toFile() }.getOrDefault(binary)
                File(real.parentFile?.parentFile, "lib").takeIf { it.isDirectory }
            }

        fun daemonLogFile(): File =
            File(File(System.getProperty("user.home") ?: ".", ".kartographer"), "daemon.log")
    }
}
