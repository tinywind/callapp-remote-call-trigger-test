package com.example.remotecallreceiver

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CallHttpServer(
    private val port: Int,
    private val token: String,
    private val hasCallPermission: () -> Boolean,
    private val hasHangUpPermission: () -> Boolean,
    private val placeCall: (String) -> Unit,
    private val hangUpCall: () -> Boolean,
    private val onStatus: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        executor.execute { runServer() }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        executor.shutdownNow()
    }

    private fun runServer() {
        try {
            ServerSocket(port).use { socket ->
                serverSocket = socket

                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (error: Exception) {
                        if (running.get()) {
                            onStatus("Server accept failed: ${error.message}")
                        }
                        break
                    }

                    handleClient(client)
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                onStatus("Server failed: ${error.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(" ")

                if (requestParts.size < 2) {
                    writeJson(client, "400 Bad Request", "Invalid request line.")
                    return
                }

                val method = requestParts[0]
                val path = requestParts[1]
                val headers = readHeaders(reader)

                if (method == "OPTIONS") {
                    writeJson(client, "204 No Content", "")
                    return
                }

                if (method != "POST" || path !in setOf("/call", "/hangup")) {
                    writeJson(client, "404 Not Found", "Use POST /call or POST /hangup.")
                    return
                }

                if (headers["x-call-token"] != token) {
                    writeJson(client, "401 Unauthorized", "Invalid token.")
                    return
                }

                when (path) {
                    "/call" -> handleCall(client, reader, headers)
                    "/hangup" -> handleHangUp(client)
                }
            } catch (error: Exception) {
                writeJson(client, "500 Internal Server Error", error.message ?: "Unknown server error.")
            }
        }
    }

    private fun handleCall(client: Socket, reader: BufferedReader, headers: Map<String, String>) {
        val body = readBody(reader, headers["content-length"]?.toIntOrNull() ?: 0)
        val number = parsePhoneNumber(body)

        if (number == null) {
            writeJson(client, "400 Bad Request", "Invalid phone number.")
            return
        }

        if (!hasCallPermission()) {
            writeJson(client, "403 Forbidden", "CALL_PHONE permission is not granted.")
            return
        }

        placeCall(number)
        writeJson(client, "202 Accepted", "Call request accepted.")
    }

    private fun handleHangUp(client: Socket) {
        if (!hasHangUpPermission()) {
            writeJson(client, "403 Forbidden", "ANSWER_PHONE_CALLS permission is not granted.")
            return
        }

        if (hangUpCall()) {
            writeJson(client, "202 Accepted", "Hang up request accepted.")
        } else {
            writeJson(client, "409 Conflict", "No active call was ended.")
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        while (true) {
            val line = reader.readLine() ?: break

            if (line.isEmpty()) {
                break
            }

            val separator = line.indexOf(":")

            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = value
            }
        }

        return headers
    }

    private fun readBody(reader: BufferedReader, contentLength: Int): String {
        if (contentLength <= 0) {
            return ""
        }

        val buffer = CharArray(contentLength)
        var totalRead = 0

        while (totalRead < contentLength) {
            val read = reader.read(buffer, totalRead, contentLength - totalRead)

            if (read < 0) {
                break
            }

            totalRead += read
        }

        return String(buffer, 0, totalRead)
    }

    private fun parsePhoneNumber(body: String): String? {
        val number = try {
            JSONObject(body).optString("number").trim()
        } catch (_: Exception) {
            return null
        }

        return number.takeIf { PHONE_NUMBER_PATTERN.matches(it) }
    }

    private fun writeJson(socket: Socket, status: String, message: String) {
        val body = if (message.isBlank()) {
            ""
        } else {
            JSONObject().put("message", message).toString()
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Call-Token\r\n")
            append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        socket.getOutputStream().use { output ->
            output.write(response.toByteArray(StandardCharsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private companion object {
        val PHONE_NUMBER_PATTERN = Regex("""^[+0-9*#]{2,32}$""")
    }
}
