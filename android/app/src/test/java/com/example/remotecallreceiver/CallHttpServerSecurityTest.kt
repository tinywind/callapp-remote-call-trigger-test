package com.example.remotecallreceiver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class CallHttpServerSecurityTest {
    @Test
    fun rejectsThePublicLegacyToken() {
        Fixture("dev-token").use { fixture ->
            assertTrue(fixture.request("dev-token").startsWith("HTTP/1.1 401"))
            assertEquals(0, fixture.actions.get())
        }
    }

    @Test
    fun rejectsAnUnconfiguredToken() {
        Fixture("").use { fixture ->
            assertTrue(fixture.request("").startsWith("HTTP/1.1 401"))
            assertEquals(0, fixture.actions.get())
        }
    }

    @Test
    fun rejectsMissingAndWrongTokensBeforeAnyAction() {
        Fixture("fixture-private-shared-token").use { fixture ->
            for (path in listOf("/call", "/hangup", "/sms/send", "/sms/backup")) {
                for (token in listOf(null, "wrong", "dev-token")) {
                    assertTrue(fixture.request(token, path).startsWith("HTTP/1.1 401"))
                }
            }
            assertEquals(0, fixture.actions.get())
        }
    }

    @Test
    fun acceptsOnlyTheExplicitConfiguredToken() {
        Fixture("fixture-private-shared-token").use { fixture ->
            assertTrue(fixture.request("fixture-private-shared-token").startsWith("HTTP/1.1 202"))
            assertEquals(1, fixture.actions.get())
        }
    }

    private class Fixture(token: String) : AutoCloseable {
        val actions = AtomicInteger()
        private val port = ServerSocket(0).use { it.localPort }
        private val server = CallHttpServer(
            port, token, { true }, { true }, { true }, { true },
            { actions.incrementAndGet() }, { actions.incrementAndGet(); true },
            { _, _ -> actions.incrementAndGet() }, { actions.incrementAndGet(); JSONObject() }, {}
        )

        init { server.start() }

        fun request(token: String?, path: String = "/hangup"): String {
            return connect().use { socket ->
                val header = token?.let { "X-Call-Token: $it\r\n" }.orEmpty()
                val request = "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n${header}Content-Length: 0\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            }
        }

        private fun connect(): Socket {
            repeat(100) {
                try { return Socket("127.0.0.1", port).apply { soTimeout = 2000 } }
                catch (_: ConnectException) { Thread.sleep(5) }
            }
            throw AssertionError("Fixture listener did not start")
        }

        override fun close() { server.stop() }
    }
}
