package com.hugo.projector

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import com.hugo.projector.fx.FxSceneGraph

/** Small projector-hosted HTTP/WebSocket server. No desktop runtime is needed. */
class LocalController(
    private val context: Context,
    private val command: (String, JSONObject, (Boolean, String) -> Unit) -> Unit,
    private val status: (Boolean) -> JSONObject,
    private val onPairingRequest: (String) -> Unit,
    private val onStateChange: () -> Unit,
) {
    val store = SceneStore(context)
    val vault = TokenVault(context)
    private val random = SecureRandom()
    val internalMediaSecret: String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val pool = Executors.newFixedThreadPool(8)
    private val snapshots = Executors.newSingleThreadExecutor()
    private val snapshotDirty = AtomicBoolean(false)
    private val snapshotRunning = AtomicBoolean(false)
    private val peers = Collections.synchronizedSet(mutableSetOf<Peer>())
    private val recent = LinkedHashMap<String, JSONObject>()
    private val activeClients = AtomicInteger()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var pairingCode = ""
    @Volatile private var pairingExpiry = 0L
    @Volatile private var pending: PairRequest? = null
    @Volatile private var editor: Peer? = null
    private var failedPairs = 0
    private var failureWindow = 0L

    data class PairRequest(val id: String, val name: String, val token: String, val created: Long, var approved: Boolean = false, var rejected: Boolean = false)
    private data class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>, val length: Long, val loopback: Boolean)

    fun start() {
        if (running) return
        running = true
        thread(name = "hugo-local-http", isDaemon = true) {
            try {
                ServerSocket(39090).use { socket ->
                    server = socket
                    Log.i("HUGO_V2", "Local controller listening on :39090")
                    while (running) {
                        val client = socket.accept()
                        if (activeClients.incrementAndGet() > 8) {
                            activeClients.decrementAndGet()
                            client.close()
                        } else pool.execute {
                            try { handle(client) } catch (e: Exception) { Log.w("HUGO_V2", "HTTP client: ${e.message}") }
                            finally { activeClients.decrementAndGet(); try { client.close() } catch (_: Exception) {} }
                        }
                    }
                }
            } catch (e: SocketException) {
                if (running) Log.e("HUGO_V2", "Server socket closed", e)
            } catch (e: Exception) { Log.e("HUGO_V2", "Server failed", e) }
        }
        thread(name = "hugo-heartbeat", isDaemon = true) {
            while (running) {
                try { Thread.sleep(5_000); broadcast(JSONObject().put("type", "heartbeat").put("status", status(false))) }
                catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        synchronized(peers) { peers.toList() }.forEach { it.close() }
        pool.shutdownNow()
        snapshots.shutdownNow()
    }

    fun address(): String? {
        if (PortableMode.state == "active") PortableMode.hotspotAddress?.let { return it }
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        val candidates = interfaces.filter { it.isUp && !it.isLoopback }
        for (network in candidates.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }) {
            for (ip in network.inetAddresses.toList()) {
                if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) return ip.hostAddress
            }
        }
        return null
    }

    fun url(): String? = address()?.let { "http://$it:39090/" }

    @Synchronized fun activatePairing(): String {
        pairingCode = (100000 + random.nextInt(900000)).toString()
        pairingExpiry = System.currentTimeMillis() + 120_000
        pending = null
        failedPairs = 0
        failureWindow = System.currentTimeMillis()
        onStateChange()
        return pairingCode
    }

    fun activeCode(): String? = pairingCode.takeIf { it.isNotEmpty() && System.currentTimeMillis() < pairingExpiry }
    fun pairSecondsLeft(): Long = ((pairingExpiry - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    fun pendingName(): String? = pending?.takeIf { !it.approved && !it.rejected && System.currentTimeMillis() - it.created < 120_000 }?.name

    @Synchronized fun approvePairing(): Boolean {
        val request = pending ?: return false
        if (request.rejected || request.approved || System.currentTimeMillis() - request.created > 120_000) return false
        vault.add(request.id, request.name, request.token)
        request.approved = true
        pairingCode = ""
        onStateChange()
        return true
    }

    @Synchronized fun rejectPairing() {
        pending?.rejected = true
        onStateChange()
    }

    fun revoke(id: String): Boolean {
        val token = vault.tokenFor(id)
        val removed = vault.revoke(id)
        if (removed && token != null) synchronized(peers) { peers.filter { it.token == token }.toList() }.forEach {
            if (editor == it) editor = null
            it.close()
        }
        onStateChange()
        return removed
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val first = readLine(input) ?: return
        val parts = first.split(' ')
        if (parts.size < 3 || parts[1].length > 2048) return
        val headers = mutableMapOf<String, String>()
        var totalHeader = first.length
        while (true) {
            val line = readLine(input) ?: return
            totalHeader += line.length
            if (totalHeader > 16_384) return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val host = headers["host"] ?: ""
        val validHosts = listOf("127.0.0.1", "localhost") + PortableMode.addresses().values
        if (host.substringBefore(':') !in validHosts || host.substringAfter(':', "39090") != "39090") return respond(output, 403, "Forbidden")
        val origin = headers["origin"]
        if (origin != null && origin != "http://$host") return respond(output, 403, "Invalid origin")
        val target = parts[1]
        val request = Request(parts[0], target.substringBefore('?'), target.substringAfter('?', ""), headers, headers["content-length"]?.toLongOrNull() ?: 0, socket.inetAddress.isLoopbackAddress)
        if (headers["upgrade"]?.lowercase() == "websocket" && request.path == "/ws") return websocket(socket, input, output, request)
        try { route(request, input, output) }
        catch (e: Exception) { respond(output, 400, JSONObject().put("error", e.message ?: "Bad request").toString(), "application/json") }
    }

    private fun route(req: Request, input: InputStream, out: OutputStream) {
        when {
            req.method == "GET" && req.path in listOf("/", "/app.js", "/style.css") -> {
                val file = when (req.path) { "/" -> "index.html"; "/app.js" -> "app.js"; else -> "style.css" }
                val mime = when (file) { "index.html" -> "text/html"; "app.js" -> "application/javascript"; else -> "text/css" }
                val bytes = context.assets.open("controller/$file").use { it.readBytes() }
                respondBytes(out, 200, bytes, "$mime; charset=utf-8")
            }
            req.method == "GET" && req.path.startsWith("/media/") -> {
                val internal = req.loopback && req.headers["x-hugo-internal"] == internalMediaSecret
                if (!internal && !authorized(req)) respond(out, 401, "Unauthorized")
                else media(req, out)
            }
            req.method == "POST" && req.path == "/api/pair/request" -> {
                val body = bodyJson(req, input)
                val code = body.optString("code")
                val name = body.optString("name").take(60).ifBlank { "Phone" }
                val request = synchronized(this) {
                    val now = System.currentTimeMillis()
                    if (now - failureWindow > 120_000) { failedPairs = 0; failureWindow = now }
                    if (failedPairs >= 5) throw IllegalArgumentException("Pairing locked temporarily")
                    if (code != pairingCode || now >= pairingExpiry || code.isEmpty()) {
                        failedPairs++
                        throw IllegalArgumentException("Invalid or expired code")
                    }
                    if (pendingName() != null) throw IllegalArgumentException("Another pairing request is pending")
                    PairRequest(store.newId(), name, store.newId() + store.newId(), now).also { pending = it }
                }
                onPairingRequest(name)
                json(out, JSONObject().put("requestId", request.id).put("state", "pending"))
            }
            req.method == "GET" && req.path == "/api/pair/status" -> {
                val id = URLDecoder.decode(req.query.substringAfter("id=", ""), "UTF-8")
                val request = pending?.takeIf { it.id == id }
                val answer = when {
                    request == null -> JSONObject().put("state", "expired")
                    request.rejected -> JSONObject().put("state", "rejected")
                    request.approved -> JSONObject().put("state", "approved").put("token", request.token).also { pending = null }
                    System.currentTimeMillis() - request.created > 120_000 -> JSONObject().put("state", "expired")
                    else -> JSONObject().put("state", "pending")
                }
                json(out, answer)
            }
            req.path.startsWith("/api/") -> {
                if (!authorized(req)) return respond(out, 401, "{\"error\":\"Pair first\"}", "application/json")
                val mutation = req.method != "GET"
                if (mutation && (editor?.token != req.headers["authorization"]?.removePrefix("Bearer ") || editor?.sessionId != req.headers["x-hugo-session"]))
                    return respond(out, 403, "{\"error\":\"Viewer only\"}", "application/json")
                when {
                    req.method == "GET" && req.path == "/api/state" -> respond(out,200,withScenes(JSONObject().put("protocol",2).put("status",status(true)).put("editorConnected",editor!=null)),"application/json; charset=utf-8")
                    req.method == "GET" && req.path == "/api/scenes" -> respond(out,200,store.scenesJson(),"application/json; charset=utf-8")
                    req.method == "POST" && req.path == "/api/scenes" -> {
                        val body = bodyJson(req, input)
                        val scene = body.getJSONObject("scene")
                        validateScene(scene)
                        json(out, store.save(body.getString("name"), scene))
                    }
                    req.method == "DELETE" && req.path.startsWith("/api/scenes/") -> json(out, JSONObject().put("deleted", store.delete(req.path.substringAfterLast('/'))))
                    req.method == "POST" && req.path == "/api/upload" -> upload(req, input, out)
                    req.method == "POST" && req.path == "/api/command" -> {
                        val ack = execute(bodyJson(req, input))
                        json(out, ack)
                        if (ack.optBoolean("ok")) scheduleSnapshot()
                    }
                    else -> respond(out, 404, "Not found")
                }
            }
            else -> respond(out, 404, "Not found")
        }
    }

    private fun authorized(req: Request): Boolean {
        val auth = req.headers["authorization"] ?: return false
        return auth.startsWith("Bearer ") && vault.valid(auth.substring(7))
    }

    private fun bodyJson(req: Request, input: InputStream): JSONObject {
        require(req.length in 1..1_000_000) { "Invalid body size" }
        val bytes = ByteArray(req.length.toInt())
        readFully(input, bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    @Synchronized private fun execute(message: JSONObject): JSONObject {
        val started = SystemClock.elapsedRealtime()
        require(message.optInt("version") == 2) { "Invalid protocol version" }
        val id = message.optString("command_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid command ID" }
        recent[id]?.let { return JSONObject(it.toString()).put("duplicate", true) }
        val now = System.currentTimeMillis()
        val expiry = message.optLong("expiresAt")
        require(expiry > now && expiry <= now + 60_000) { "Command expired" }
        val action = message.optString("command")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        require(action in listOf("show", "clear", "volume", "status", "canvas_batch", "canvas_undo", "canvas_redo", "mapping", "animation", "game_start", "game_pause", "game_resume", "game_exit", "media_place", "media_pause", "media_resume", "media_stop") || (BuildConfig.FX_ENABLED && action in FxSceneGraph.commands)) { "Unsupported command" }
        if (action == "show") validateScene(payload)
        if (action == "volume") require(payload.optInt("percent", -1) in 0..100) { "Invalid volume" }
        val latch = CountDownLatch(1)
        var ok = false
        var detail = "Timed out"
        command(action, payload) { result, text -> ok = result; detail = text; latch.countDown() }
        latch.await(25, TimeUnit.SECONDS)
        val dispatchMs = SystemClock.elapsedRealtime() - started
        val current = status(false)
        val result = JSONObject().put("type", "ack").put("command_id", id).put("ok", ok).put("detail", detail)
            .put("status", JSONObject(current.toString()).apply { remove("canvas") })
        recent[id] = result
        while (recent.size > 512) recent.remove(recent.keys.first())
        Log.i("HUGO_V2", "command=$action dispatchMs=$dispatchMs statusMs=${SystemClock.elapsedRealtime() - started - dispatchMs}")
        return result
    }

    private fun validateScene(scene: JSONObject) {
        if(scene.optString("kind") in listOf("image","video"))MediaPlacement.parse(scene.optJSONObject("placement"))
        when (scene.optString("kind")) {
            "fx" -> { require(BuildConfig.FX_ENABLED); FxSceneGraph.validateScene(scene) }
            "text" -> require(scene.optString("text").length <= 20_000) { "Text too long" }
            "html" -> require(scene.optString("html").length <= 200_000) { "HTML too long" }
            "page" -> require(scene.optString("url").startsWith("https://") || scene.optString("url").startsWith("http://")) { "Invalid page URL" }
            "image", "video" -> require(scene.optString("src").matches(Regex("/media/[a-f0-9]{32}\\.(png|jpg|webp|gif|mp4|webm)")) || scene.optString("src").startsWith("https://")) { "Invalid media URL" }
            "canvas" -> require(scene.toString().length <= 500_000) { "Canvas too large" }
            else -> throw IllegalArgumentException("Unknown scene kind")
        }
    }

    private fun upload(req: Request, input: InputStream, out: OutputStream) {
        val mime = req.headers["content-type"]?.substringBefore(';') ?: ""
        val extension = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif", "video/mp4" to "mp4", "video/webm" to "webm")[mime]
            ?: throw IllegalArgumentException("Unsupported media type")
        require(store.canStore(req.length)) { "Library limit or free-space reserve reached" }
        val name = "${store.newId()}.$extension"
        val temporary = File(store.mediaDir, "$name.part")
        val target = File(store.mediaDir, name)
        try {
            temporary.outputStream().use { file ->
                val buffer = ByteArray(16_384)
                var left = req.length
                while (left > 0) {
                    val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (count < 0) throw IllegalArgumentException("Upload interrupted")
                    file.write(buffer, 0, count)
                    left -= count
                }
                file.flush()
            }
            require(temporary.renameTo(target)) { "Cannot save media" }
            json(out, JSONObject().put("src", "/media/$name").put("bytes", target.length()))
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    private fun media(req: Request, out: OutputStream) {
        val name = req.path.substringAfterLast('/')
        if (!name.matches(Regex("[a-f0-9]{32}\\.(png|jpg|webp|gif|mp4|webm)"))) return respond(out, 404, "Not found")
        val file = File(store.mediaDir, name)
        if (!file.isFile) return respond(out, 404, "Not found")
        val ext = name.substringAfterLast('.')
        val mime = mapOf("png" to "image/png", "jpg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "mp4" to "video/mp4", "webm" to "video/webm")[ext] ?: return
        val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(req.headers["range"] ?: "")
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = match?.groupValues?.get(2)?.toLongOrNull() ?: (file.length() - 1)
        if (start < 0 || end < start || end >= file.length()) return respond(out, 416, "Invalid range")
        val partial = match != null
        val length = end - start + 1
        val extra = if (partial) "Content-Range: bytes $start-$end/${file.length()}\r\n" else ""
        header(out, if (partial) 206 else 200, mime, length, "Accept-Ranges: bytes\r\n$extra")
        file.inputStream().use { stream ->
            stream.skip(start)
            val buffer = ByteArray(16_384)
            var left = length
            while (left > 0) {
                val count = stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (count < 0) break
                out.write(buffer, 0, count)
                left -= count
            }
        }
        out.flush()
    }

    private fun websocket(socket: Socket, input: InputStream, out: OutputStream, req: Request) {
        val key = req.headers["sec-websocket-key"] ?: return respond(out, 400, "Missing key")
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        out.flush()
        // Unauthenticated peers must not occupy all HTTP worker threads indefinitely.
        socket.soTimeout = 10_000
        val peer = Peer(socket, out, store.newId())
        try {
            while (running) {
                val opcode = input.read()
                if (opcode < 0) break
                val sizeByte = input.read()
                if (sizeByte < 0 || opcode and 0x80 == 0 || sizeByte and 0x80 == 0) break
                var size = (sizeByte and 127).toLong()
                if (size == 126L) size = ((input.read() shl 8) or input.read()).toLong()
                if (size == 127L) { size = 0; repeat(8) { size = (size shl 8) or input.read().toLong() } }
                if (size < 0 || size > 131_072) break
                val mask = ByteArray(4); readFully(input, mask)
                val data = ByteArray(size.toInt()); readFully(input, data)
                for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode and 15) {
                    8 -> break
                    9 -> peer.frame(10, data)
                    1 -> {
                        val message = JSONObject(String(data, Charsets.UTF_8))
                        if (!peer.authenticated) {
                            if (message.optString("type") != "auth" || !vault.valid(message.optString("token"))) break
                            peer.authenticated = true
                            peer.token = message.getString("token")
                            socket.soTimeout = 0
                            peers.add(peer)
                            synchronized(this) { if (editor == null) editor = peer }
                            peer.sendEncoded(withScenes(JSONObject().put("type", "welcome").put("role", if (editor == peer) "editor" else "viewer")
                                .put("sessionId", peer.sessionId)
                                .put("status", status(true))))
                        } else if (!vault.valid(peer.token)) {
                            break
                         } else if (message.optString("type") in listOf("game_join", "game_input", "game_pause")) {
                            try {
                                val type = message.optString("type")
                                val result = when(type) {
                                    "game_join" -> TankDuel.join(peer.token, peer.sessionId, message.optInt("player")-1, editor == peer)
                                    "game_pause" -> { require(TankDuel.slots.any { it.token == peer.token && it.session == peer.sessionId }) { "Join a player first" }; TankDuel.pause(); TankDuel.state() }
                                    else -> { TankDuel.input(peer.token, peer.sessionId, message); TankDuel.state() }
                                }
                                peer.send(JSONObject().put("type", "game_state").put("game", result))
                            } catch(e:Exception) { peer.send(JSONObject().put("type", "game_error").put("detail", e.message)) }
                        } else if (message.optString("type") == "release") {
                            synchronized(this) { if (editor == peer) editor = null }
                            peer.send(JSONObject().put("type", "role").put("role", "viewer"))
                        } else if (message.optString("type") == "claim") {
                            synchronized(this) { if (editor == null || editor == peer) editor = peer }
                            peer.send(JSONObject().put("type", "role").put("role", if (editor == peer) "editor" else "viewer")
                                .put("sessionId", if (editor == peer) peer.sessionId else ""))
                        } else if (message.optString("type") == "command") {
                            if (editor != peer) peer.send(JSONObject().put("type", "ack").put("command_id", message.optString("command_id")).put("ok", false).put("detail", "Viewer only"))
                            else {
                                if (message.optString("command") == "canvas_batch") {
                                    val seq = message.optJSONObject("payload")?.optLong("seq") ?: 0L
                                    val duplicate = synchronized(this) { recent.containsKey(message.optString("command_id")) }
                                    if (seq <= 0 || (seq <= peer.lastSeq && !duplicate)) {
                                        peer.send(JSONObject().put("type", "ack").put("command_id", message.optString("command_id")).put("ok", false).put("detail", "Out-of-order canvas batch"))
                                        continue
                                    }
                                    peer.lastSeq = maxOf(peer.lastSeq, seq)
                                }
                                val ack = execute(message)
                                peer.send(ack)
                                if (ack.optBoolean("ok")) scheduleSnapshot()
                            }
                        }
                    }
                }
            }
        } finally {
            TankDuel.disconnect(peer.sessionId)
            peers.remove(peer)
            synchronized(this) { if (editor == peer) editor = null }
        }
    }

    private fun broadcast(message: JSONObject) {
        synchronized(peers) { peers.toList() }.forEach {
            if (!vault.valid(it.token)) { if (editor == it) editor = null; it.close(); peers.remove(it) }
            else try { it.send(message) } catch (_: Exception) { it.close(); peers.remove(it) }
        }
    }

    private fun scheduleSnapshot() {
        snapshotDirty.set(true)
        if (!snapshotRunning.compareAndSet(false, true)) return
        snapshots.execute {
            try {
                do {
                    snapshotDirty.set(false)
                    broadcast(JSONObject().put("type", "snapshot").put("status", status(true)))
                } while (running && snapshotDirty.get())
            } catch (e: Exception) { Log.w("HUGO_V2", "Snapshot broadcast failed", e) }
            finally {
                snapshotRunning.set(false)
                if (running && snapshotDirty.get()) scheduleSnapshot()
            }
        }
    }

    private class Peer(private val socket: Socket, private val out: OutputStream, val sessionId: String) {
        @Volatile var authenticated = false
        @Volatile var token = ""
        var lastSeq = 0L
        @Synchronized fun send(value: JSONObject) = frame(1, value.toString().toByteArray(Charsets.UTF_8))
        @Synchronized fun sendEncoded(value: String) = frame(1,value.toByteArray(Charsets.UTF_8))
        @Synchronized fun frame(opcode: Int, data: ByteArray) {
            val prefix = ByteArrayOutputStream()
            prefix.write(0x80 or opcode)
            when {
                data.size < 126 -> prefix.write(data.size)
                data.size < 65536 -> { prefix.write(126); prefix.write(data.size shr 8); prefix.write(data.size and 255) }
                else -> { prefix.write(127); repeat(4) { prefix.write(0) }; prefix.write(data.size shr 24); prefix.write(data.size shr 16); prefix.write(data.size shr 8); prefix.write(data.size) }
            }
            out.write(prefix.toByteArray()); out.write(data); out.flush()
        }
        fun close() = try { socket.close() } catch (_: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val next = input.read()
            if (next < 0) return null
            if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(next)
        }
        return null
    }

    private fun readFully(input: InputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val count = input.read(data, offset, data.size - offset)
            if (count < 0) throw IllegalArgumentException("Body truncated")
            offset += count
        }
    }

    private fun withScenes(value: JSONObject): String = value.toString().dropLast(1)+",\"scenes\":"+store.scenesJson()+"}"
    private fun json(out: OutputStream, value: Any) = respond(out, 200, value.toString(), "application/json; charset=utf-8")
    private fun respond(out: OutputStream, code: Int, text: String, mime: String = "text/plain; charset=utf-8") = respondBytes(out, code, text.toByteArray(Charsets.UTF_8), mime)
    private fun respondBytes(out: OutputStream, code: Int, bytes: ByteArray, mime: String) {
        header(out, code, mime, bytes.size.toLong())
        out.write(bytes); out.flush()
    }
    private fun header(out: OutputStream, code: Int, mime: String, length: Long, extra: String = "") {
        val reason = mapOf(200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 416 to "Range Not Satisfiable")[code] ?: "Error"
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\nContent-Length: $length\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n$extra\r\n".toByteArray())
    }
}
