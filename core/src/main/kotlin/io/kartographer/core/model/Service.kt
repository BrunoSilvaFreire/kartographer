package io.kartographer.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class TaskLifecycle { FINITE, SERVICE }

enum class DependencyCondition { READY, COMPLETED }

sealed interface ReadinessCondition {
    data class Tcp(val host: String, val port: Int) : ReadinessCondition
    data class Http(val url: String) : ReadinessCondition
    data class Log(val pattern: Regex) : ReadinessCondition
    data object Signal : ReadinessCondition
}

fun tcp(host: String, port: Int): ReadinessCondition = ReadinessCondition.Tcp(host, port)
fun http(url: String): ReadinessCondition = ReadinessCondition.Http(url)
fun log(pattern: Regex): ReadinessCondition = ReadinessCondition.Log(pattern)
fun signal(): ReadinessCondition = ReadinessCondition.Signal

class ServiceSpec {
    var readiness: ReadinessCondition? = null
    var timeout: Duration = 60.seconds

    internal fun validated(): ServiceSpec = apply {
        require(readiness != null) { "A service must declare exactly one readiness condition." }
        require(timeout.isPositive() || timeout == Duration.INFINITE) {
            "Service readiness timeout must be positive or infinite."
        }
    }
}

