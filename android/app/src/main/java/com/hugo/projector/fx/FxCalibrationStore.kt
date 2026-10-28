package com.hugo.projector.fx

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Separate from Canvas/library/preferences. Atomic commits retain the previous good file. */
class FxCalibrationStore(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"fx-calibration-v1.json"))
    private fun read(): JSONObject = try { JSONObject(file.openRead().use { it.reader().readText() }) }
        catch(e: java.io.FileNotFoundException) { JSONObject() }
    @Synchronized fun list(): JSONArray {
        val all=read(); return JSONArray(all.keys().asSequence().map { id -> JSONObject().put("id",id).put("name",all.getJSONObject(id).optString("target_label")) }.toList())
    }
    @Synchronized fun load(id: String): JSONObject {
        val p=read().optJSONObject(id) ?: throw IllegalArgumentException("Save this calibration profile first")
        FxSceneGraph.patches(p); return p
    }
    @Synchronized fun save(profile: JSONObject): JSONObject {
        FxSceneGraph.patches(profile)
        val p=JSONObject(profile.toString()).put("updated_at",System.currentTimeMillis())
        val all=read(); require(all.has(p.getString("calibration_profile_id")) || all.length()<20) { "Maximum 20 profiles" }
        all.put(p.getString("calibration_profile_id"),p)
        val stream=file.startWrite()
        try { stream.write(all.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch(e: Exception) { file.failWrite(stream); throw e }
        return p
    }
}
