package com.hugo.projector

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/** Single authoritative vector model shared by the phone preview and projector renderer. */
class CanvasEngine(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "canvas-draft-v1.json"))
    private val handler = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "hugo-canvas-save").apply { isDaemon = true } }
    private val saveTask = Runnable {
        val captured = synchronized(this) { model }
        saveExecutor.execute { saveNow(captured) }
    }
    private data class History(val state: JSONObject, val strokeId: String? = null)
    private val undo = ArrayDeque<History>()
    private val redo = ArrayDeque<History>()
    private var model = load() ?: emptyModel()
    private var batching = false

    @Synchronized fun snapshot(): JSONObject = cloneModel(model)

    /** Called only on the UI thread immediately after mutations; the renderer consumes it synchronously. */
    @Synchronized fun modelForRender(): JSONObject = model

    @Synchronized fun replace(next: JSONObject) {
        validate(next)
        model = cloneModel(next)
        undo.clear(); redo.clear(); scheduleSave()
    }

    @Synchronized fun batch(payload: JSONObject): String {
        val actions = payload.optJSONArray("actions") ?: throw IllegalArgumentException("Missing actions")
        require(actions.length() in 1..64) { "Invalid action batch" }
        val before = model
        model = cloneModel(before)
        batching = true
        try {
            for (i in 0 until actions.length()) apply(actions.getJSONObject(i))
            validate(model)
            val strokeId = strokeGroup(actions)
            if (strokeId == null || undo.lastOrNull()?.strokeId != strokeId) undo.addLast(History(before, strokeId))
            while (undo.size > 50) undo.removeFirst()
            redo.clear()
            scheduleSave()
        } catch (e: Exception) {
            model = before
            throw e
        } finally {
            batching = false
        }
        return "canvas model updated"
    }

    @Synchronized fun undo(): Boolean {
        if (undo.isEmpty()) return false
        val entry = undo.removeLast()
        redo.addLast(History(model, entry.strokeId))
        model = entry.state
        scheduleSave(); return true
    }

    @Synchronized fun redo(): Boolean {
        if (redo.isEmpty()) return false
        val entry = redo.removeLast()
        undo.addLast(History(model, entry.strokeId))
        model = entry.state
        scheduleSave(); return true
    }

    @Synchronized fun mapping(payload: JSONObject) {
        val corners = payload.getJSONArray("corners")
        validateCorners(corners)
        remember()
        model.put("corners", JSONArray(corners.toString()))
        model.put("grid", payload.optBoolean("grid"))
        scheduleSave()
    }

    @Synchronized fun animation(payload: JSONObject) {
        val type = payload.optString("type")
        require(type in listOf("none", "draw", "pulse", "type")) { "Invalid effect" }
        val speed = payload.optInt("speed")
        require(speed in 1..10) { "Invalid effect speed" }
        remember()
        model.put("effect", JSONObject().put("type", type).put("speed", speed)
            .put("loop", payload.optBoolean("loop")).put("color", payload.optString("color", "#75a5ff")))
        scheduleSave()
    }

    private fun apply(action: JSONObject) {
        when (action.optString("type")) {
            "stroke_start" -> {
                val id = action.getString("stroke_id")
                require(id.length in 8..80 && model.getJSONArray("strokes").length() < 300) { "Stroke limit reached" }
                val strokes = model.getJSONArray("strokes")
                require((0 until strokes.length()).none { strokes.getJSONObject(it).optString("stroke_id") == id }) { "Duplicate stroke ID" }
                val width = action.getDouble("width")
                require(width in 1.0..60.0) { "Invalid width" }
                val point = checkedPoint(action.getJSONArray("point"))
                remember()
                model.getJSONArray("strokes").put(JSONObject().put("stroke_id", id).put("color", action.optString("color", "#75a5ff"))
                    .put("width", width).put("points", JSONArray().put(point)))
            }
            "stroke_point" -> {
                val strokes = model.getJSONArray("strokes")
                val index = (strokes.length() - 1 downTo 0).firstOrNull { strokes.getJSONObject(it).optString("stroke_id") == action.optString("stroke_id") }
                    ?: throw IllegalArgumentException("Unknown stroke")
                val original = strokes.getJSONObject(index)
                val points = JSONArray()
                val oldPoints = original.getJSONArray("points")
                for (i in 0 until oldPoints.length()) points.put(oldPoints.getJSONArray(i))
                val stroke = JSONObject().put("stroke_id", original.optString("stroke_id"))
                    .put("color", original.optString("color")).put("width", original.optDouble("width")).put("points", points)
                require(stroke.getJSONArray("points").length() < 4000 && pointCount() < 10000) { "Point limit reached" }
                stroke.getJSONArray("points").put(checkedPoint(action.getJSONArray("point")))
                strokes.put(index, stroke)
            }
            "stroke_end" -> Unit
            "text_add" -> {
                val text = action.optString("text")
                require(text.isNotBlank() && text.length <= 200 && model.getJSONArray("texts").length() < 50) { "Invalid text" }
                val id = action.getString("id")
                val texts = model.getJSONArray("texts")
                require((0 until texts.length()).none { texts.getJSONObject(it).optString("id") == id }) { "Duplicate text ID" }
                val x = action.getDouble("x"); val y = action.getDouble("y")
                require(x in 0.0..1.0 && y in 0.0..1.0) { "Invalid text position" }
                remember()
                model.getJSONArray("texts").put(JSONObject().put("id", id).put("text", text).put("x", x).put("y", y)
                    .put("color", action.optString("color", "#ffffff")).put("size", action.optInt("size", 48).coerceIn(12, 160)))
            }
            "text_move" -> {
                val texts = model.getJSONArray("texts")
                val index = (0 until texts.length()).firstOrNull { texts.getJSONObject(it).optString("id") == action.optString("id") }
                    ?: throw IllegalArgumentException("Unknown text")
                val item = JSONObject(texts.getJSONObject(index).toString())
                val x = action.getDouble("x"); val y = action.getDouble("y")
                require(x in 0.0..1.0 && y in 0.0..1.0) { "Invalid text position" }
                remember(); item.put("x", x).put("y", y); texts.put(index, item)
            }
            "erase_at" -> {
                val p = checkedPoint(action.getJSONArray("point"))
                val x = p.getDouble(0); val y = p.getDouble(1)
                val strokes = model.getJSONArray("strokes")
                var closest = -1; var distance = .04
                for (i in 0 until strokes.length()) {
                    val points = strokes.getJSONObject(i).getJSONArray("points")
                    for (j in 0 until points.length()) {
                        val q = points.getJSONArray(j)
                        val d = hypot((q.getDouble(0)-x)*1.777, q.getDouble(1)-y)
                        if (d < distance) { distance = d; closest = i }
                    }
                }
                if (closest >= 0) {
                    remember()
                    val next = JSONArray()
                    for (i in 0 until strokes.length()) if (i != closest) next.put(strokes.getJSONObject(i))
                    model.put("strokes", next)
                }
            }
            "corners" -> {
                val corners = action.getJSONArray("corners")
                validateCorners(corners); remember(); model.put("corners", JSONArray(corners.toString()))
            }
            "grid" -> { remember(); model.put("grid", action.optBoolean("enabled")) }
            "mask" -> {
                val mask = action.getJSONArray("points")
                require(mask.length() in 3..12) { "Mask needs 3–12 points" }
                for (i in 0 until mask.length()) checkedPoint(mask.getJSONArray(i))
                require(abs(area(mask)) > .005) { "Mask is too small" }
                remember(); model.put("mask", JSONArray(mask.toString()))
            }
            "clear_canvas" -> { model.put("strokes", JSONArray()).put("texts", JSONArray()) }
            "mask_clear" -> { remember(); model.put("mask", JSONArray()) }
            else -> throw IllegalArgumentException("Unknown canvas action")
        }
    }

    private fun remember() {
        if (batching) return
        undo.addLast(History(model))
        while (undo.size > 50) undo.removeFirst()
        redo.clear()
        model = cloneModel(model)
    }

    /** Consecutive batches from one pointer stroke share one undo step. */
    private fun strokeGroup(actions: JSONArray): String? {
        var id: String? = null
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            if (action.optString("type") !in listOf("stroke_start", "stroke_point", "stroke_end")) return null
            val current = action.optString("stroke_id")
            if (current.isBlank() || id != null && id != current) return null
            id = current
        }
        return id
    }

    /** Shared nested values are immutable; edits to a stroke or text replace that item first. */
    private fun cloneModel(source: JSONObject): JSONObject {
        val copy = JSONObject()
            .put("schema", source.optInt("schema", 1))
            .put("corners", source.optJSONArray("corners") ?: JSONArray("[[0,0],[1,0],[1,1],[0,1]]"))
            .put("mask", source.optJSONArray("mask") ?: JSONArray())
            .put("grid", source.optBoolean("grid"))
            .put("effect", source.optJSONObject("effect") ?: JSONObject().put("type", "none").put("speed", 5).put("loop", true))
        val strokes = JSONArray()
        val sourceStrokes = source.getJSONArray("strokes")
        for (i in 0 until sourceStrokes.length()) strokes.put(sourceStrokes.getJSONObject(i))
        val texts = JSONArray()
        val originalTexts = source.getJSONArray("texts")
        for (i in 0 until originalTexts.length()) texts.put(originalTexts.getJSONObject(i))
        copy.put("strokes", strokes).put("texts", texts)
        return copy
    }

    private fun checkedPoint(value: JSONArray): JSONArray {
        require(value.length() == 2) { "Invalid point" }
        val x = value.getDouble(0); val y = value.getDouble(1)
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) { "Point outside canvas" }
        return JSONArray().put(x).put(y)
    }

    private fun validateCorners(corners: JSONArray) {
        require(corners.length() == 4) { "Four corners required" }
        for (i in 0..3) checkedPoint(corners.getJSONArray(i))
        require(abs(area(corners)) > .03) { "Degenerate surface" }
        var orientation = 0.0
        for (i in 0..3) {
            val a = corners.getJSONArray(i)
            val b = corners.getJSONArray((i + 1) % 4)
            val c = corners.getJSONArray((i + 2) % 4)
            val cross = (b.getDouble(0)-a.getDouble(0))*(c.getDouble(1)-b.getDouble(1)) -
                (b.getDouble(1)-a.getDouble(1))*(c.getDouble(0)-b.getDouble(0))
            require(abs(cross) > .001) { "Corners are nearly collinear" }
            if (orientation == 0.0) orientation = cross else require(cross * orientation > 0) { "Surface must be convex" }
        }
        val src = floatArrayOf(0f,0f,1280f,0f,1280f,720f,0f,720f)
        val dst = FloatArray(8)
        for (i in 0..3) { dst[2*i]=(corners.getJSONArray(i).getDouble(0)*1280).toFloat(); dst[2*i+1]=(corners.getJSONArray(i).getDouble(1)*720).toFloat() }
        require(Matrix().setPolyToPoly(src,0,dst,0,4)) { "Invalid homography" }
    }

    private fun area(points: JSONArray): Double {
        var sum = 0.0
        for (i in 0 until points.length()) {
            val a = points.getJSONArray(i); val b = points.getJSONArray((i+1)%points.length())
            sum += a.getDouble(0)*b.getDouble(1)-b.getDouble(0)*a.getDouble(1)
        }
        return sum/2
    }

    private fun pointCount(): Int {
        val strokes = model.getJSONArray("strokes")
        return (0 until strokes.length()).sumOf { strokes.getJSONObject(it).getJSONArray("points").length() }
    }

    private fun validate(candidate: JSONObject) {
        require(candidate.getJSONArray("strokes").length() <= 300 && candidate.getJSONArray("texts").length() <= 50) { "Canvas limit" }
        validateCorners(candidate.getJSONArray("corners"))
        val strokes = candidate.getJSONArray("strokes")
        var points = 0
        for (i in 0 until strokes.length()) points += strokes.getJSONObject(i).getJSONArray("points").length()
        require(points <= 10000) { "Canvas point limit" }
    }

    private fun scheduleSave() { handler.removeCallbacks(saveTask); handler.postDelayed(saveTask, 800) }
    private fun saveNow(captured: JSONObject) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(captured.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) file.failWrite(stream)
            Log.e("HUGO_V2", "Canvas draft save failed", e)
        }
    }

    private fun load(): JSONObject? = try { JSONObject(String(file.openRead().use { it.readBytes() }, Charsets.UTF_8)) } catch (_: Exception) { null }
    private fun emptyModel(): JSONObject = JSONObject().put("schema", 1).put("strokes", JSONArray()).put("texts", JSONArray())
        .put("corners", JSONArray("[[0,0],[1,0],[1,1],[0,1]]")).put("mask", JSONArray()).put("grid", false)
        .put("effect", JSONObject().put("type", "none").put("speed", 5).put("loop", true).put("color", "#75a5ff"))
}
