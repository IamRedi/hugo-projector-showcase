package com.hugo.projector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sin

/** Vector renderer. Geometry is prepared on model changes and reused by animation frames. */
class ProjectionCanvasView(context: Context) : View(context) {
    private data class Stroke(val id: String, val source: JSONObject, val path: Path, val measure: PathMeasure, val length: Float, val color: Int, val width: Float)
    private data class Label(val text: String, val x: Float, val y: Float, val color: Int, val size: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
    private var strokes = emptyList<Stroke>()
    private var labels = emptyList<Label>()
    private var transform = Matrix()
    private var clip = Path().apply { addRect(0f, 0f, 1280f, 720f, Path.Direction.CW) }
    private var grid = false
    private var effectType = "none"
    private var effectSpeed = 5
    private var effectLoop = true
    private var effectColor = Color.rgb(117, 165, 255)
    private var animationStart = SystemClock.uptimeMillis()
    @Volatile var lastFrameAt = 0L
        private set
    @Volatile var measuredFps = 0.0
        private set
    private var fpsStart = 0L
    private var frames = 0

    fun setModel(next: JSONObject, restartAnimation: Boolean = false) {
        val corners = next.optJSONArray("corners") ?: JSONArray("[[0,0],[1,0],[1,1],[0,1]]")
        val source = floatArrayOf(0f,0f,1280f,0f,1280f,720f,0f,720f)
        val target = FloatArray(8)
        for (i in 0..3) {
            val point = corners.getJSONArray(i)
            target[i*2] = (point.getDouble(0) * 1280).toFloat()
            target[i*2+1] = (point.getDouble(1) * 720).toFloat()
        }
        transform = Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) }
        clip = Path()
        val mask = next.optJSONArray("mask")
        if (mask != null && mask.length() >= 3) {
            for (i in 0 until mask.length()) {
                val point = mask.getJSONArray(i)
                val x = (point.getDouble(0) * 1280).toFloat()
                val y = (point.getDouble(1) * 720).toFloat()
                if (i == 0) clip.moveTo(x, y) else clip.lineTo(x, y)
            }
            clip.close()
        } else clip.addRect(0f, 0f, 1280f, 720f, Path.Direction.CW)
        grid = next.optBoolean("grid")
        val effect = next.optJSONObject("effect") ?: JSONObject()
        effectType = effect.optString("type", "none")
        effectSpeed = effect.optInt("speed", 5).coerceIn(1, 10)
        effectLoop = effect.optBoolean("loop", true)
        effectColor = parseColor(effect.optString("color", "#75a5ff"))
        val inputStrokes = next.optJSONArray("strokes") ?: JSONArray()
        val previous = strokes.associateBy { it.id }
        strokes = (0 until inputStrokes.length()).mapNotNull { i ->
            val item = inputStrokes.getJSONObject(i)
            val points = item.optJSONArray("points") ?: return@mapNotNull null
            if (points.length() == 0) return@mapNotNull null
            val id = item.optString("stroke_id")
            previous[id]?.takeIf { it.source === item }?.let { return@mapNotNull it }
            val path = Path()
            for (index in 0 until points.length()) {
                val point = points.getJSONArray(index)
                val x = (point.getDouble(0) * 1280).toFloat()
                val y = (point.getDouble(1) * 720).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (points.length() == 1) {
                val point = points.getJSONArray(0)
                path.lineTo((point.getDouble(0) * 1280 + .1).toFloat(), (point.getDouble(1) * 720 + .1).toFloat())
            }
            val measure = PathMeasure(path, false)
            Stroke(id, item, path, measure, measure.length, parseColor(item.optString("color", "#75a5ff")), item.optDouble("width", 8.0).toFloat())
        }
        val inputLabels = next.optJSONArray("texts") ?: JSONArray()
        labels = (0 until inputLabels.length()).map { i ->
            val item = inputLabels.getJSONObject(i)
            Label(item.optString("text"), (item.optDouble("x", .5) * 1280).toFloat(), (item.optDouble("y", .5) * 720).toFloat(), parseColor(item.optString("color", "#ffffff")), item.optDouble("size", 48.0).toFloat())
        }
        if (restartAnimation) animationStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val now = SystemClock.uptimeMillis()
        lastFrameAt = now
        if (fpsStart == 0L) fpsStart = now
        frames++
        if (now - fpsStart >= 2000) { measuredFps = frames * 1000.0 / (now - fpsStart); fpsStart = now; frames = 0 }
        canvas.save()
        canvas.scale(width / 1280f, height / 720f)
        canvas.concat(transform)
        canvas.clipPath(clip)
        if (grid) drawGrid(canvas)
        val duration = (6500L / effectSpeed).coerceAtLeast(500)
        val elapsed = now - animationStart
        val fraction = if (effectLoop) (elapsed % duration).toFloat() / duration else (elapsed.toFloat() / duration).coerceAtMost(1f)
        val pulse = ((sin(elapsed / (400.0 / effectSpeed)) + 1.0) / 2.0).toFloat()
        paint.style = Paint.Style.STROKE
        val segment = if (effectType == "draw") Path() else null
        for (stroke in strokes) {
            paint.strokeWidth = stroke.width
            paint.color = animatedColor(stroke.color, pulse)
            if (effectType == "draw") {
                segment!!.reset()
                stroke.measure.getSegment(0f, stroke.length * fraction, segment, true)
                canvas.drawPath(segment, paint)
            } else canvas.drawPath(stroke.path, paint)
        }
        paint.style = Paint.Style.FILL
        for (label in labels) {
            paint.textSize = label.size
            paint.color = animatedColor(label.color, pulse)
            val shown = if (effectType == "type") label.text.take((label.text.length * fraction).toInt()) else label.text
            canvas.drawText(shown, label.x, label.y, paint)
        }
        canvas.restore()
        if (effectType != "none" && (effectLoop || fraction < 1f)) postInvalidateOnAnimation()
    }

    private fun drawGrid(canvas: Canvas) {
        paint.color = Color.rgb(90, 120, 155)
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        for (x in 0..1280 step 80) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 720f, paint)
        for (y in 0..720 step 80) canvas.drawLine(0f, y.toFloat(), 1280f, y.toFloat(), paint)
    }

    private fun animatedColor(original: Int, pulse: Float): Int {
        if (effectType != "pulse") return original
        val ratio = pulse * .7f
        return Color.rgb(
            (Color.red(original) * (1 - ratio) + Color.red(effectColor) * ratio).toInt(),
            (Color.green(original) * (1 - ratio) + Color.green(effectColor) * ratio).toInt(),
            (Color.blue(original) * (1 - ratio) + Color.blue(effectColor) * ratio).toInt(),
        )
    }

    private fun parseColor(value: String): Int = try { Color.parseColor(value) } catch (_: Exception) { Color.WHITE }
}
