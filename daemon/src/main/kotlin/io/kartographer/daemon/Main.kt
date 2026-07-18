package io.kartographer.daemon

import io.kartographer.core.plugins.PluginLoader
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("io.kartographer.daemon.Main")

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 50051
    
    logger.info("Initializing Kartographer PluginLoader...")
    PluginLoader.initialize()
    logger.info("Loaded modules: {}", PluginLoader.getLoadedModules().joinToString { it.id })
    
    val server = DaemonServer(port)
    server.start()
    server.blockUntilShutdown()
}
