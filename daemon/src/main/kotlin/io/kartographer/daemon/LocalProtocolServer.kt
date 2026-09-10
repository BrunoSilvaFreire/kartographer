package io.kartographer.daemon

import io.kartographer.core.daemon.LocalProtocol
import io.kartographer.daemon.proto.LocalRequest
import io.kartographer.daemon.proto.LocalResponse
import io.kartographer.daemon.proto.RunClientMessage
import io.kartographer.daemon.proto.localError
import io.kartographer.daemon.proto.localResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the CLI over a plain Unix socket, so a one-second CLI process never pays gRPC's
 * startup cost. See [LocalProtocol] for the measurements behind that decision.
 *
 * Thread-per-connection: connections are one per CLI invocation and short-lived, so a
 * selector loop would add complexity for no benefit, and a blocking handler lets the
 * service's suspending API be called directly.
 */
class LocalProtocolServer(
    private val socketPath: File,
    private val service: KartographerDaemonService
) : Closeable {

    companion object {
        private val logger = LoggerFactory.getLogger(LocalProtocolServer::class.java)
    }

    private val running = AtomicBoolean(false)
    private var serverChannel: ServerSocketChannel? = null

    val isRunning: Boolean
        get() = running.get()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "karto-local-conn").apply { isDaemon = true }
    }

    fun start() {
        prepareSocketPath()

        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath.toPath()))
        serverChannel = channel
        running.set(true)
        restrictToOwner(socketPath)

        val acceptor = Thread({ acceptLoop(channel) }, "karto-local-accept")
        acceptor.isDaemon = true
        acceptor.start()

        logger.info("Kartographer Daemon listening on unix socket {}", socketPath.absolutePath)
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (running.get()) {
            val connection = try {
                channel.accept()
            } catch (e: ClosedChannelException) {
                return
            } catch (e: Exception) {
                if (running.get()) logger.warn("Accept failed: {}", e.localizedMessage)
                return
            }
            workers.execute { handle(connection) }
        }
    }

    private fun handle(connection: SocketChannel) {
        connection.use {
            try {
                // One connection carries a whole CLI invocation, which is usually a liveness
                // probe followed by the real request — so keep serving until the client hangs
                // up rather than closing after the first answer.
                while (true) {
                    val frame = LocalProtocol.readFrame(connection) ?: return
                    val request = LocalRequest.parseFrom(frame)
                    if (request.requestCase == LocalRequest.RequestCase.RUN) {
                        // A run is a conversation: events stream out while prompt answers and
                        // a possible cancel stream back. It owns the connection from here.
                        handleRun(connection, request.run)
                        return
                    }
                    respond(connection, handleUnary(request))
                }
            } catch (e: Exception) {
                logger.debug("Local connection ended: {}", e.localizedMessage)
                runCatching {
                    respond(connection, localResponse {
                        error = localError { message = e.localizedMessage ?: e.toString(); code = 1 }
                    })
                }
            }
        }
    }

    private fun handleUnary(request: LocalRequest): LocalResponse = runBlocking {
        when (request.requestCase) {
            LocalRequest.RequestCase.DAEMON_INFO ->
                localResponse { daemonInfo = service.getDaemonInfo(request.daemonInfo) }
            LocalRequest.RequestCase.DESCRIBE_PROJECT ->
                localResponse { describeProject = service.describeProject(request.describeProject) }
            LocalRequest.RequestCase.COMPLETE ->
                localResponse { complete = service.complete(request.complete) }
            LocalRequest.RequestCase.SHUTDOWN ->
                localResponse { shutdown = service.shutdown(request.shutdown) }
            LocalRequest.RequestCase.QUERY_RUNS ->
                localResponse { queryRuns = service.queryRuns(request.queryRuns) }
            else -> localResponse {
                error = localError { message = "Unsupported request ${request.requestCase}"; code = 2 }
            }
        }
    }

    private fun handleRun(connection: SocketChannel, start: RunClientMessage) = runBlocking {
        val outgoing = Channel<RunClientMessage>(Channel.UNLIMITED)
        outgoing.trySend(start)

        // Follow-up frames (prompt answers, cancel) arrive while the run is streaming, so
        // they need their own reader.
        val reader = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val frame = LocalProtocol.readFrame(connection) ?: break
                    val message = LocalRequest.parseFrom(frame)
                    if (message.requestCase == LocalRequest.RequestCase.RUN) {
                        outgoing.trySend(message.run)
                    }
                }
            } catch (e: Exception) {
                // Client vanished; closing the request side unblocks the run.
            } finally {
                outgoing.close()
            }
        }

        try {
            service.runPipeline(outgoing.consumeAsFlow()).collect { message ->
                respond(connection, localResponse { run = message })
            }
        } finally {
            outgoing.close()
            reader.cancel()
        }
    }

    @Synchronized
    private fun respond(connection: SocketChannel, response: LocalResponse) {
        LocalProtocol.writeFrame(connection, response.toByteArray())
    }

    /**
     * A leftover socket file from a killed daemon makes bind fail with "address already in
     * use", so it is cleared first. The parent directory is 0700 for the same reason the
     * socket is 0600.
     */
    private fun prepareSocketPath() {
        val parent = socketPath.absoluteFile.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) {
            error("Could not create daemon socket directory ${parent.absolutePath}")
        }
        restrictToOwner(parent, directory = true)
        if (socketPath.exists()) socketPath.delete()
    }

    private fun restrictToOwner(file: File, directory: Boolean = false) {
        val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE)
        try {
            Files.setPosixFilePermissions(file.toPath(), permissions)
        } catch (e: Exception) {
            // Not a reason to refuse to serve, but it does mean the socket is unprotected.
            logger.warn("Could not restrict permissions on {}: {}", file.absolutePath, e.localizedMessage)
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverChannel?.close() }
        workers.shutdownNow()
        socketPath.delete()
    }
}
