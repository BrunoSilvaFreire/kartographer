package io.kartographer.tasks.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FlutterEndpointsTest {

    @Test
    fun `parses Dart VM Service http url to ws endpoint`() {
        val line = "A Dart VM Service on Linux is available at: http://127.0.0.1:41234/aBcDeFg=/"
        val endpoints = parseFlutterEndpoints(line)

        assertEquals("http://127.0.0.1:41234/aBcDeFg=/", endpoints.vmServiceUri)
        assertEquals("ws://127.0.0.1:41234/aBcDeFg=/ws", endpoints.debugEndpoint)
        assertNull(endpoints.devToolsUri)
    }

    @Test
    fun `parses Flutter DevTools url`() {
        val line = "The Flutter DevTools debugger and profiler on Linux is available at: http://127.0.0.1:9100?uri=http://127.0.0.1:41234/token="
        val endpoints = parseFlutterEndpoints(line)

        assertEquals("http://127.0.0.1:9100?uri=http://127.0.0.1:41234/token=", endpoints.devToolsUri)
        assertNull(endpoints.vmServiceUri)
        assertNull(endpoints.debugEndpoint)
    }

    @Test
    fun `parses Connecting to VM Service ws url directly`() {
        val line = "Connecting to VM Service at ws://127.0.0.1:41234/aBcDeFg=/ws"
        val endpoints = parseFlutterEndpoints(line)

        assertEquals("ws://127.0.0.1:41234/aBcDeFg=/ws", endpoints.vmServiceUri)
        assertEquals("ws://127.0.0.1:41234/aBcDeFg=/ws", endpoints.debugEndpoint)
    }

    @Test
    fun `ignores unrelated stdout lines`() {
        val line = "Launching lib/main.dart on Linux in debug mode..."
        val endpoints = parseFlutterEndpoints(line)

        assertNull(endpoints.vmServiceUri)
        assertNull(endpoints.debugEndpoint)
        assertNull(endpoints.devToolsUri)
    }
}
