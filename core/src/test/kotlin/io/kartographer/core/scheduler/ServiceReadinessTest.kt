package io.kartographer.core.scheduler

import com.sun.net.httpserver.HttpServer
import io.kartographer.core.model.http
import io.kartographer.core.model.tcp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

class ServiceReadinessTest {
    @Test
    fun `tcp readiness succeeds when port accepts connections`() = runBlocking {
        ServerSocket(0).use { server ->
            withTimeout(2_000) { awaitNetworkReadiness(tcp("127.0.0.1", server.localPort)) }
        }
    }

    @Test
    fun `http readiness requires a successful response`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ready") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            withTimeout(2_000) {
                awaitNetworkReadiness(http("http://127.0.0.1:${server.address.port}/ready"))
            }
        } finally {
            server.stop(0)
        }
    }
}
