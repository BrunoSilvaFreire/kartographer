package io.kartographer.daemon

import io.kartographer.core.daemon.DaemonEndpoint
import io.kartographer.core.plugins.PluginLoader
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("io.kartographer.daemon.Main")

private const val USAGE = """
Usage: kartographer-daemon [options]

  --socket <path>        Unix socket to listen on (default: ${'$'}XDG_RUNTIME_DIR/kartographer/daemon.sock)
  --no-socket            Do not listen on a Unix socket
  --listen-tcp <port>    Also listen on a TCP port. Off by default: the daemon runs arbitrary
                         task code as you, and a loopback port is reachable by every process
                         on the machine, whereas the socket is 0600.
  --idle-timeout <mins>  Exit after this many minutes with no runs (default: 180, 0 disables)
  --help                 Show this message
"""

private class Options(
    val socket: File?,
    val tcpPort: Int?,
    val idleTimeoutMinutes: Long
)

private fun parse(args: Array<String>): Options {
    var socket: File? = DaemonEndpoint.socketPath()
    var tcpPort: Int? = null
    var idleTimeout = 180L

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                println(USAGE.trimIndent())
                exitProcess(0)
            }
            "--socket" -> socket = File(args.getOrNull(++i) ?: fail("--socket needs a path"))
            "--no-socket" -> socket = null
            "--listen-tcp" -> tcpPort = (args.getOrNull(++i) ?: fail("--listen-tcp needs a port")).toIntOrNull()
                ?: fail("--listen-tcp needs a numeric port")
            "--idle-timeout" -> idleTimeout = (args.getOrNull(++i) ?: fail("--idle-timeout needs minutes")).toLongOrNull()
                ?: fail("--idle-timeout needs a number of minutes")
            // Historical form: a bare port. Kept so existing scripts and units keep working.
            else -> tcpPort = arg.toIntOrNull() ?: fail("Unrecognized argument '$arg'")
        }
        i++
    }

    return Options(socket, tcpPort, idleTimeout)
}

private fun fail(message: String): Nothing {
    System.err.println("kartographer-daemon: $message")
    System.err.println(USAGE.trimIndent())
    exitProcess(2)
}

fun main(args: Array<String>) {
    val options = parse(args)

    logger.info("Initializing Kartographer PluginLoader...")
    PluginLoader.initialize()
    logger.info("Loaded modules: {}", PluginLoader.getLoadedModules().joinToString { it.id })

    val server = DaemonServer(tcpPort = options.tcpPort, socketPath = options.socket)
    server.start()

    if (options.idleTimeoutMinutes > 0) {
        startIdleWatchdog(server, options.idleTimeoutMinutes)
    }

    server.blockUntilShutdown()
}

/**
 * Exits an idle daemon so a machine does not accumulate warm JVMs holding compiled pipelines
 * for projects nobody is working on any more. The CLI restarts one on the next invocation.
 */
private fun startIdleWatchdog(server: DaemonServer, idleMinutes: Long) {
    val idleMillis = idleMinutes * 60_000
    val thread = Thread {
        var lastBusyAt = System.currentTimeMillis()
        while (true) {
            Thread.sleep(30_000)
            if (server.service.isBusy) {
                lastBusyAt = System.currentTimeMillis()
                continue
            }
            if (System.currentTimeMillis() - lastBusyAt >= idleMillis) {
                logger.info("Idle for {} minutes; shutting down.", idleMinutes)
                server.stop()
                return@Thread
            }
        }
    }
    thread.isDaemon = true
    thread.name = "kartographer-idle-watchdog"
    thread.start()
}
