package com.hugo.projector

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** Projector-owned user library. Cache cleanup never touches these files. */
class SceneStore(context: Context) {
    private val root = File(context.filesDir, "v2-library").apply { mkdirs() }
    private val scenesFile = AtomicFile(File(root, "scenes-v1.json"))
    val mediaDir = File(root, "media").apply { mkdirs() }
    private val random = SecureRandom()
    private var cachedScenesJson: String? = null

    /** Reuse the exact serialized library; large saved drawings need not be re-encoded on every ACK/refresh. */
    @Synchronized fun scenesJson(): String {
        cachedScenesJson?.let { return it }
        val raw=try { String(scenesFile.openRead().use { it.readBytes() },Charsets.UTF_8).also { JSONArray(it) } }
            catch(_: Exception) { "[]" }
        cachedScenesJson=raw
        return raw
    }

    @Synchronized fun scenes(): JSONArray = try {
        JSONArray(scenesJson())
    } catch (_: Exception) { JSONArray() }

    @Synchronized fun save(name: String, scene: JSONObject): JSONObject {
        require(name.isNotBlank() && name.length <= 80) { "Invalid scene name" }
        val id = newId()
        val entry = JSONObject().put("id", id).put("name", name).put("scene", scene)
        // Append the new record without re-encoding thousands of retained drawing coordinates.
        val raw=scenesJson().trim()
        val empty=raw.substring(1,raw.length-1).isBlank()
        writeEncoded(raw.dropLast(1)+(if(empty) "" else ",")+entry.toString()+"]")
        return entry
    }

    @Synchronized fun delete(id: String): Boolean {
        val all = scenes()
        val next = JSONArray()
        var removed = false
        for (i in 0 until all.length()) {
            val entry = all.getJSONObject(i)
            if (entry.optString("id") == id) removed = true else next.put(entry)
        }
        if (removed) write(next)
        return removed
    }

    @Synchronized fun find(id: String): JSONObject? {
        val all = scenes()
        for (i in 0 until all.length()) {
            val entry = all.getJSONObject(i)
            if (entry.optString("id") == id) return entry
        }
        return null
    }

    @Synchronized fun mediaBytes(): Long = mediaDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun canStore(bytes: Long): Boolean {
        if (bytes < 0 || bytes > 300_000_000L || mediaBytes() + bytes > 512L * 1024 * 1024) return false
        return StatFs(root.path).availableBytes - bytes >= 512L * 1024 * 1024
    }

    fun newId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun write(array: JSONArray) {
        writeEncoded(array.toString())
    }

    private fun writeEncoded(encoded: String) {
        val stream = scenesFile.startWrite()
        try {
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            scenesFile.finishWrite(stream)
            cachedScenesJson=encoded
        } catch (e: Exception) {
            scenesFile.failWrite(stream)
            throw e
        }
    }
}
