package io.kartographer.daemon

import io.grpc.Server
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hosts the daemon on two transports with different jobs.
 *
 * The Unix socket carries the CLI's local protocol — plain framed protobuf, because gRPC
 * costs more to start than the work it would carry for a one-second process (see
 * [io.kartographer.core.daemon.LocalProtocol]).
 *
 * TCP carries gRPC for genuinely remote workers, and is off by default: the daemon runs
 * arbitrary task code as this user, and a loopback port is reachable by every process on the
 * machine whereas the socket is 0600.
 */
class DaemonServer(
    private val tcpPort: Int? = null,
    private val socketPath: File? = null,
    val service: KartographerDaemonService = KartographerDaemonService()
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DaemonServer::class.java)
    }

    private val grpcServer: Server? = tcpPort?.let {
        ServerBuilder.forPort(it).addService(service).build()
    }

    private val localServer: LocalProtocolServer? = socketPath?.let {
        LocalProtocolServer(it, service)
    }

    init {
        require(grpcServer != null || localServer != null) {
            "DaemonServer needs a TCP port, a socket path, or both"
        }
        service.onShutdownRequested = { stop() }
    }

    /** The port the TCP listener actually bound, for tests that ask for an ephemeral one. */
    val actualPort: Int
        get() = grpcServer?.port ?: error("Daemon is not listening on TCP")

    fun start() {
        localServer?.start()
        grpcServer?.let {
            it.start()
            logger.info("Kartographer Daemon listening for gRPC on TCP port {}", it.port)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down Kartographer Daemon...")
            stop()
            logger.info("Kartographer Daemon shut down.")
        })
    }

    fun stop() {
        localServer?.close()
        grpcServer?.shutdown()
        service.close()
    }

    fun blockUntilShutdown() {
        grpcServer?.awaitTermination()
        // With no gRPC listener the accept loop runs on a daemon thread, so main has to park
        // here itself or the JVM would exit the moment start() returns.
        if (grpcServer == null) {
            while (localServer?.isRunning == true) {
                Thread.sleep(200)
            }
        }
    }

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        grpcServer?.awaitTermination(timeout, unit) ?: true
}
