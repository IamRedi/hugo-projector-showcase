package com.hugo.projector

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Small RFC 6455 client. The controller uses a fresh HMAC challenge per connection. */
class AgentConnection(
    private val context: Context,
    private val token: String,
    private val onState: (Boolean, String) -> Unit,
    private val onCommand: (String, JSONObject, (Boolean, String) -> Unit) -> Unit,
    private val status: () -> JSONObject,
) {
    private val random = SecureRandom()
    private val prefs = context.getSharedPreferences("hugo_agent", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("device_id", null) ?: ("hugo-" + UUID.randomUUID().toString().take(8)).also {
        prefs.edit().putString("device_id", it).apply()
    }
    @Volatile private var running = false
    @Volatile private var authenticated = false
    @Volatile private var currentHost: String? = null
    @Volatile private var socket: Socket? = null
    private var challengeNonce: String? = null
    private var output: OutputStream? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "hugo-ws", isDaemon = true) {
            var delayMs = 1_000L
            while (running) {
                try {
                    val host = prefs.getString("controller_host", null) ?: discover()
                    if (host == null) {
                        onState(false, "Searching for controller")
                    } else {
                        currentHost = host
                        prefs.edit().putString("controller_host", host).apply()
                        connect(host)
                    }
                } catch (e: Exception) {
                    Log.w("HUGO_AGENT", "Connection lost: ${e.javaClass.simpleName}: ${e.message}")
                    onState(false, "Reconnecting")
                } finally {
                    authenticated = false
                    output = null
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }
                if (!running) break
                Thread.sleep(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(8_000L)
                // Discovery is retried after a failed saved-host connection.
                prefs.edit().remove("controller_host").apply()
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    fun mediaUrl(source: String): String {
        if (source.startsWith("https://") || source.startsWith("http://")) return source
        val host = currentHost ?: throw IllegalStateException("No controller")
        require(source.startsWith("/media/")) { "Invalid media path" }
        return "http://$host:39090$source"
    }

    private fun discover(): String? {
        onState(false, "Discovering controller")
        DatagramSocket().use { udp ->
            udp.broadcast = true
            udp.soTimeout = 2_000
            val request = "HUGO_DISCOVER_V1".toByteArray(StandardCharsets.UTF_8)
            udp.send(DatagramPacket(request, request.size, java.net.InetAddress.getByName("255.255.255.255"), 39091))
            val response = DatagramPacket(ByteArray(128), 128)
            return try {
                udp.receive(response)
                val text = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                if (text == "HUGO_CONTROLLER_V1 39090") response.address.hostAddress else null
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }

    private fun connect(host: String) {
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(host, 39090), 4_000)
        s.soTimeout = 5_000
        val input = s.getInputStream()
        val out = s.getOutputStream()
        output = out
        val keyBytes = ByteArray(16).also(random::nextBytes)
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val request = "GET /agent HTTP/1.1\r\nHost: $host:39090\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        out.write(request.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        val headers = ByteArrayOutputStream()
        while (headers.size() < 8192) {
            val b = input.read()
            if (b < 0) throw EOFException("Handshake closed")
            headers.write(b)
            val bytes = headers.toByteArray()
            if (bytes.size >= 4 && bytes.takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) break
        }
        val reply = headers.toString("US-ASCII")
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        if (!reply.startsWith("HTTP/1.1 101") || !reply.contains("Sec-WebSocket-Accept: $expected", ignoreCase = true)) {
            throw IllegalStateException("Invalid WebSocket handshake")
        }
        var lastHeartbeat = 0L
        while (running && !s.isClosed) {
            try {
                val frame = readFrame(input)
                when (frame.first) {
                    1 -> handleText(String(frame.second, StandardCharsets.UTF_8))
                    8 -> break
                    9 -> writeFrame(10, frame.second)
                }
            } catch (_: SocketTimeoutException) {
                // Wake periodically to send an application heartbeat.
            }
            val now = SystemClock.elapsedRealtime()
            if (authenticated && now - lastHeartbeat >= 5_000) {
                send(JSONObject().put("type", "heartbeat").put("deviceId", deviceId).put("status", status()))
                lastHeartbeat = now
            }
        }
    }

    private fun handleText(text: String) {
        val message = JSONObject(text)
        when (message.optString("type")) {
            "challenge" -> {
                val nonce = message.getString("nonce")
                challengeNonce = nonce
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                val proof = mac.doFinal("$nonce|$deviceId".toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
                send(JSONObject().put("type", "auth").put("deviceId", deviceId).put("proof", proof))
            }
            "auth_ok" -> {
                val nonce = challengeNonce ?: throw IllegalStateException("Missing challenge")
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                val expected = mac.doFinal("$nonce|$deviceId|server".toByteArray(StandardCharsets.UTF_8))
                val supplied = message.getString("serverProof").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(expected, supplied)) throw IllegalStateException("Controller authentication failed")
                authenticated = true
                Log.i("HUGO_AGENT", "Authenticated over Wi-Fi to $currentHost")
                onState(true, "Connected")
                send(JSONObject().put("type", "heartbeat").put("deviceId", deviceId).put("status", status()))
            }
            "command" -> {
                if (!authenticated) return
                val id = message.getString("id")
                val command = message.getString("command")
                val payload = message.optJSONObject("payload") ?: JSONObject()
                onCommand(command, payload) { ok, detail ->
                    thread(name = "hugo-ack", isDaemon = true) {
                        try {
                            send(JSONObject().put("type", "ack").put("id", id).put("ok", ok).put("detail", detail).put("status", status()))
                        } catch (e: Exception) {
                            Log.w("HUGO_AGENT", "ACK failed", e)
                        }
                    }
                }
            }
        }
    }

    @Synchronized private fun send(json: JSONObject) = writeFrame(1, json.toString().toByteArray(StandardCharsets.UTF_8))

    @Synchronized private fun writeFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("Not connected")
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 65535 -> {
                header.write(0x80 or 126)
                header.write((payload.size shr 8) and 255)
                header.write(payload.size and 255)
            }
            else -> throw IllegalArgumentException("Message too large")
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        out.write(header.toByteArray())
        val encoded = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        out.write(encoded)
        out.flush()
    }

    private fun readFrame(input: InputStream): Pair<Int, ByteArray> {
        val first = input.read().also { if (it < 0) throw EOFException() }
        val second = input.read().also { if (it < 0) throw EOFException() }
        if ((first and 0x80) == 0) throw IllegalStateException("Fragmented frames unsupported")
        var length = (second and 0x7f).toLong()
        if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
        if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }
        if (length < 0 || length > 8_000_000) throw IllegalStateException("Frame too large")
        val masked = second and 0x80 != 0
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val bytes = ByteArray(length.toInt())
        readFully(input, bytes)
        if (mask != null) for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
        return (first and 0x0f) to bytes
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }
}
