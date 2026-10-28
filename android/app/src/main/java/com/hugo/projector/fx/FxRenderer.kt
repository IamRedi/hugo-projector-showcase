package com.hugo.projector.fx

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import org.json.JSONObject
import kotlin.math.*

/** One hardware Canvas view, three 8x8 meshes, cached polygon alpha masks. */
class FxRenderer(context: Context, private val session: FxSession): View(context), Choreographer.FrameCallback {
    private data class Layer(val patch: FxPatch, val base: FloatArray, val vertices: FloatArray, val mask: Bitmap, val bounds: RectF, val path: Path)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE; textSize=20f }
    private var layers=emptyList<Layer>()
    private var face: Bitmap?=null
    private var hand: Bitmap?=null
    private var released=false
    private var scheduled=false
    private var nextNanos=0L
    private var lastDraw=0L
    private var metricsStart=0L
    private var frames=0L
    private var intervalCount=0
    private var intervalSum=0.0
    private var slowIntervals=0L
    private val intervals=LongArray(2048)
    private var intervalIndex=0
    @Volatile private var metrics=JSONObject()
    @Volatile var lastFrameAt=0L; private set

    init { setBackgroundColor(Color.BLACK); prepare() }
    fun prepare() {
        val previous=layers
        layers=FxSceneGraph.patches(session.profile).map { p ->
            previous.find { it.patch==p } ?: makeLayer(p)
        }
        previous.filter { it !in layers }.forEach { it.mask.recycle() }
        changed()
    }
    private fun makeLayer(p: FxPatch): Layer {
        val polygon=p.mask
        val bounds=RectF(polygon.minOf{it.x}*1280,polygon.minOf{it.y}*720,polygon.maxOf{it.x}*1280,polygon.maxOf{it.y}*720)
        val path=Path().apply { polygon.forEachIndexed { i,v -> if(i==0) moveTo(v.x*1280,v.y*720) else lineTo(v.x*1280,v.y*720) }; close() }
        val w=min(256,ceil(bounds.width()).toInt()).coerceAtLeast(2)
        val h=min(256,ceil(bounds.height()).toInt()).coerceAtLeast(2)
        val pixels=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) {
            val px=bounds.left+(x+.5f)*bounds.width()/w; val py=bounds.top+(y+.5f)*bounds.height()/h
            var distance=Float.MAX_VALUE
            for(i in polygon.indices) {
                val a=polygon[i]; val b=polygon[(i+1)%polygon.size]
                val dx=(b.x-a.x)*1280; val dy=(b.y-a.y)*720
                distance=min(distance,(dx*(py-a.y*720)-dy*(px-a.x*1280))/hypot(dx,dy))
            }
            val t=if(p.feather==0f) { if(distance>=0) 1f else 0f } else (distance/p.feather).coerceIn(0f,1f)
            pixels[y*w+x]=((t*t*(3-2*t)*255).toInt() shl 24) or 0x00ffffff
        }
        val mask=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        val base=FxSceneGraph.mesh(p)
        return Layer(p,base,FloatArray(base.size),mask,bounds,path)
    }
    private fun loadArt() {
        if(face!=null)return
        val options=BitmapFactory.Options().apply { inSampleSize=2; inPreferredConfig=Bitmap.Config.ARGB_8888; inScaled=false }
        face=context.assets.open("fx/spirit-face.png").use { BitmapFactory.decodeStream(it,null,options) } ?: error("Face asset missing")
        hand=context.assets.open("fx/spirit-hand.png").use { BitmapFactory.decodeStream(it,null,options) } ?: error("Hand asset missing")
    }
    fun changed() {
        if(released)return
        if(session.phase in listOf("PLAYING","PREVIEW","PAUSED")) loadArt()
        if(session.phase=="PLAYING" && !scheduled && isAttachedToWindow) {
            nextNanos=0; scheduled=true; Choreographer.getInstance().postFrameCallback(this)
        } else if(session.phase!="PLAYING" && scheduled) {
            Choreographer.getInstance().removeFrameCallback(this); scheduled=false; lastDraw=0
        }
        invalidate()
    }
    fun resetMetrics() { metricsStart=SystemClock.uptimeMillis(); frames=0; intervalCount=0; intervalSum=0.0; slowIntervals=0; intervalIndex=0; intervals.fill(0); lastDraw=0; metrics=JSONObject() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); changed() }
    override fun doFrame(frameTimeNanos: Long) {
        scheduled=false
        if(released || !isAttachedToWindow || session.phase!="PLAYING")return
        if(frameTimeNanos>=nextNanos) {
            invalidate()
            nextNanos=if(nextNanos==0L || frameTimeNanos-nextNanos>100_000_000L) frameTimeNanos+33_333_333L else nextNanos+33_333_333L
        }
        scheduled=true; Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if(released)return
        val now=SystemClock.uptimeMillis(); val begin=System.nanoTime()
        canvas.save()
        val scale=min(width/1280f,height/720f)
        canvas.translate((width-1280*scale)/2,(height-720*scale)/2); canvas.scale(scale,scale)
        when(session.phase) {
            "CALIBRATING" -> drawCalibration(canvas)
            "PLAYING", "PAUSED", "PREVIEW" -> {
                val t=(session.time(now)%9500)/1000
                for(layer in layers) drawSpirit(canvas,layer,t)
            }
        }
        canvas.restore(); lastFrameAt=now
        if(session.phase=="PLAYING") {
            if(metricsStart==0L)metricsStart=now
            frames++
            if(lastDraw>0) {
                val dt=now-lastDraw; intervals[intervalIndex++%intervals.size]=dt; intervalCount++; intervalSum+=dt
                if(dt>50)slowIntervals++
            }
            lastDraw=now
            // Publish once a second. Percentiles are computed on demand from the bounded ring.
            if(frames%30==0L) metrics=JSONObject().put("frames",frames).put("sampleMs",now-metricsStart).put("drawFps",if(intervalSum>0)intervalCount*1000.0/intervalSum else 0.0).put("intervalMeanMs",if(intervalCount>0)intervalSum/intervalCount else 0.0).put("intervalsOver50Ms",slowIntervals).put("lastDrawRecordingMs",(System.nanoTime()-begin)/1e6).put("hardwareCanvas",canvas.isHardwareAccelerated)
            session.finishIfNeeded()
            if(session.phase=="STOPPED")changed()
        }
    }
    private fun smooth(a: Double,b: Double,t: Double): Float { val x=((t-a)/(b-a)).coerceIn(0.0,1.0); return (x*x*(3-2*x)).toFloat() }
    private fun drawSpirit(canvas: Canvas,l: Layer,t: Double) {
        val p=l.patch; val isFace=p.id=="face"
        val reveal=smooth(if(isFace)2.8 else if(p.id=="left")1.2 else 1.55,if(isFace)4.9 else 3.3,t)
        val fade=1-smooth(if(isFace)7.0 else 7.4,9.25,t)
        val alpha=reveal*fade*session.intensity*p.opacity
        if(alpha<=.001f)return
        val cx=p.center.x*1280; val cy=p.center.y*720
        val breathe=(.976+.020*sin(t*2.3)).toFloat()
        val growth=(.87f+.13f*reveal)*breathe
        val angle=(sin(t*1.05)*(if(isFace).025 else .018)).toFloat(); val cs=cos(angle); val sn=sin(angle)
        for(y in 0..8) for(x in 0..8) {
            val i=(y*9+x)*2; val src=(y*9+if(p.id=="right")8-x else x)*2
            val dx=(l.base[src]-cx)*growth; val dy=(l.base[src+1]-cy)*growth
            val w=sin(x/8f*PI).toFloat()*sin(y/8f*PI).toFloat()
            l.vertices[i]=cx+dx*cs-dy*sn+(sin(t*1.7)*2.0*w).toFloat()
            l.vertices[i+1]=cy+dx*sn+dy*cs+(1-reveal)*14+(sin(t*2.1)*2*w).toFloat()
        }
        val save=canvas.saveLayer(l.bounds,null)
        paint.alpha=(alpha*255).toInt()
        canvas.drawBitmapMesh(if(isFace)face!! else hand!!,8,8,l.vertices,0,null,0,paint)
        canvas.drawBitmap(l.mask,null,l.bounds,maskPaint)
        canvas.restoreToCount(save)
    }
    private fun drawCalibration(canvas: Canvas) {
        line.style=Paint.Style.STROKE; line.strokeWidth=1f; line.color=Color.rgb(35,49,65)
        for(x in 0..1280 step 80)canvas.drawLine(x.toFloat(),0f,x.toFloat(),720f,line)
        for(y in 0..720 step 80)canvas.drawLine(0f,y.toFloat(),1280f,y.toFloat(),line)
        for(l in layers) {
            val active=l.patch.id==session.selected
            line.style=Paint.Style.FILL; line.color=if(active)0x223fe4cd else 0x113f80ff; canvas.drawPath(l.path,line)
            line.style=Paint.Style.STROKE; line.strokeWidth=if(active)3f else 1.5f; line.color=if(active)Color.rgb(85,235,210) else Color.rgb(94,125,175)
            canvas.drawPath(l.path,line)
            for(y in 0..8) for(x in 0..8) {
                val i=(y*9+x)*2
                if(x<8)canvas.drawLine(l.base[i],l.base[i+1],l.base[i+2],l.base[i+3],line)
                if(y<8)canvas.drawLine(l.base[i],l.base[i+1],l.base[i+18],l.base[i+19],line)
            }
            line.style=Paint.Style.FILL
            l.patch.corners.forEachIndexed { i,p -> canvas.drawCircle(p.x*1280,p.y*720,6f,line); canvas.drawText("${i+1}",p.x*1280+10,p.y*720-8,line) }
            val c=l.patch.center; canvas.drawLine(c.x*1280-12,c.y*720,c.x*1280+12,c.y*720,line); canvas.drawLine(c.x*1280,c.y*720-12,c.x*1280,c.y*720+12,line)
            canvas.drawText(l.patch.id.uppercase(),l.bounds.left,l.bounds.top-16,line)
        }
    }
    fun statistics(): JSONObject {
        val sorted=intervals.filter { it>0 }.sorted()
        return JSONObject(metrics.toString()).put("lastFrameUptimeMs",lastFrameAt).put("loopScheduled",scheduled).put("decodedArtBytes",(face?.allocationByteCount?:0)+(hand?.allocationByteCount?:0)).put("maskBytes",layers.sumOf { it.mask.allocationByteCount }).put("intervalP95Ms",if(sorted.isEmpty())JSONObject.NULL else sorted[((sorted.size-1)*.95).toInt()]).put("intervalMaxMs",sorted.lastOrNull()?:JSONObject.NULL).put("timingScope","Android draw callbacks; optical latency not measured")
    }
    fun release() {
        if(released)return
        released=true; Choreographer.getInstance().removeFrameCallback(this); scheduled=false
        layers.forEach { it.mask.recycle() }; layers=emptyList(); face?.recycle(); hand?.recycle(); face=null; hand=null
    }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
}
