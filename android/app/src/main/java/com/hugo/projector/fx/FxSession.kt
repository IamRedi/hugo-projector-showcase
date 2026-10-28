package com.hugo.projector.fx

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/** Lazy session. Monotonic timeline keeps pause/resume and speed changes continuous. */
class FxSession(context: Context) {
    val store=FxCalibrationStore(context)
    var profile=FxSceneGraph.defaults(); private set
    var phase="IDLE"; private set
    var selected="face"; private set
    var intensity=.70f; private set
    var speed=1f; private set
    var loop=false; private set
    private var elapsed=0.0
    private var started=SystemClock.uptimeMillis()
    fun time(now: Long=SystemClock.uptimeMillis()): Double = elapsed + if(phase=="PLAYING") (now-started)*speed.toDouble() else 0.0
    fun setProfile(p: JSONObject) { FxSceneGraph.patches(p); profile=JSONObject(p.toString()) }
    fun calibrate(p: JSONObject) { val id=p.optString("selected",selected); require(id in FxSceneGraph.ids); setProfile(p.optJSONObject("profile")?:profile); selected=id; phase="CALIBRATING" }
    fun resetPatch(id: String) {
        require(id in FxSceneGraph.ids) { "Unknown patch" }
        val next=JSONObject(profile.toString()); val patches=next.getJSONArray("patches")
        val defaults=FxSceneGraph.defaults().getJSONArray("patches")
        val original=(0..2).map { defaults.getJSONObject(it) }.first { it.getString("id")==id }
        for(i in 0..2)if(patches.getJSONObject(i).getString("id")==id)patches.put(i,original)
        setProfile(next); selected=id; phase="CALIBRATING"
    }
    fun settings(p: JSONObject) {
        FxSceneGraph.validateSettings(p); elapsed=time(); started=SystemClock.uptimeMillis()
        intensity=p.optDouble("intensity",intensity.toDouble()).toFloat(); speed=p.optDouble("speed",speed.toDouble()).toFloat(); loop=p.optBoolean("loop",loop)
    }
    fun play() { elapsed=0.0; started=SystemClock.uptimeMillis(); phase="PLAYING" }
    fun pause() { if(phase=="PLAYING") { elapsed=time(); phase="PAUSED" } }
    fun resume() { require(phase=="PAUSED") { "Pause before Resume" }; started=SystemClock.uptimeMillis(); phase="PLAYING" }
    fun stop() { phase="STOPPED"; elapsed=0.0 }
    fun preview() { elapsed=5500.0; phase="PREVIEW" }
    fun finishIfNeeded() { if(!loop && phase=="PLAYING" && time()>=9500) stop() }
    fun scene(): JSONObject=JSONObject().put("kind","fx").put("effect","sofa-spirit").put("profileId",profile.getString("calibration_profile_id")).put("intensity",intensity).put("speed",speed).put("loop",loop)
    @Synchronized fun status(): JSONObject=JSONObject().put("phase",phase).put("effect","sofa-spirit").put("profile",JSONObject(profile.toString())).put("profiles",store.list()).put("selected",selected).put("intensity",intensity).put("speed",speed).put("loop",loop).put("timelineMs",time().toLong()).put("durationMs",9500).put("visualAcceptance","OWNER_VISUAL_PENDING")
}
