package io.kartographer.core.scheduler

import io.kartographer.core.model.ReadinessCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal suspend fun awaitNetworkReadiness(condition: ReadinessCondition) {
    when (condition) {
        is ReadinessCondition.Tcp -> awaitTcp(condition)
        is ReadinessCondition.Http -> awaitHttp(condition)
        else -> Unit
    }
}

private suspend fun awaitTcp(condition: ReadinessCondition.Tcp) {
    require(condition.port in 1..65535) { "Invalid TCP readiness port ${condition.port}." }
    while (true) {
        val ready = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(condition.host, condition.port), 500) }
            }.isSuccess
        }
        if (ready) return
        delay(200)
    }
}

private suspend fun awaitHttp(condition: ReadinessCondition.Http) {
    val uri = URI.create(condition.url)
    require(uri.scheme == "http" || uri.scheme == "https") {
        "HTTP readiness URL must use http or https: ${condition.url}"
    }
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(1)).GET().build()
    while (true) {
        val status = withContext(Dispatchers.IO) {
            runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull()
        }
        if (status != null && status in 200..299) return
        delay(200)
    }
}
