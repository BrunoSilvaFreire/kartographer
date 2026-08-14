package io.kartographer.core.daemon

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.io.File

/**
 * Where the local daemon listens.
 *
 * A Unix socket rather than a TCP port because the daemon executes arbitrary task code as the
 * user who started it: file permissions on the socket are the access boundary, whereas a
 * loopback port is reachable by every process on the machine.
 *
 * Deliberately free of gRPC/Netty references on the local path — see [LocalProtocol] for why
 * touching that stack costs more than the work it would carry.
 */
object DaemonEndpoint {
    /** Overrides the socket path. Mainly for tests and for running several daemons side by side. */
    const val SOCKET_ENV = "KARTOGRAPHER_DAEMON_SOCKET"

    /**
     * Preferred over `$HOME` because a tmpfs runtime dir is already per-user, 0700, and cleared
     * on logout — so a stale socket cannot outlive the session that made it.
     */
    fun socketPath(): File {
        System.getenv(SOCKET_ENV)?.takeIf { it.isNotBlank() }?.let { return File(it) }

        val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
        return if (runtimeDir != null) {
            File(File(runtimeDir, "kartographer"), "daemon.sock")
        } else {
            File(File(System.getProperty("user.home") ?: ".", ".kartographer"), "daemon.sock")
        }
    }

    /** Dials a daemon over TCP — the genuinely-remote worker case, which does speak gRPC. */
    fun channelForTcp(host: String, port: Int): ManagedChannel =
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()
}
