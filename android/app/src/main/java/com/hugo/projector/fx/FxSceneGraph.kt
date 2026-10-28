package com.hugo.projector.fx

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class FxPoint(val x: Float, val y: Float)
data class FxPatch(val id: String, val corners: List<FxPoint>, val center: FxPoint, val mask: List<FxPoint>, val feather: Float, val opacity: Float, val z: Int)

/** Versioned, bounded data only. No executable/imported packs or network assets. */
object FxSceneGraph {
    val commands=setOf("fx.list","fx.status","fx.calibration.create","fx.calibration.update","fx.calibration.reset","fx.calibration.save","fx.calibration.load","fx.preview","fx.play","fx.pause","fx.resume","fx.stop","fx.replay","fx.settings","fx.setIntensity","fx.scene.save","fx.exit")
    val ids = listOf("face", "left", "right")
    fun defaults(): JSONObject = JSONObject("""{"schema_version":1,"calibration_profile_id":"sofa-default","projector_device_id":"local-projector","target_label":"Sofa · Position A","canvas_width":1280,"canvas_height":720,"patches":[{"id":"face","corners":[[0.38,0.20],[0.62,0.20],[0.62,0.72],[0.38,0.72]],"center":[0.50,0.46],"feather":14,"opacity":1,"z":1},{"id":"left","corners":[[0.16,0.40],[0.35,0.40],[0.35,0.82],[0.16,0.82]],"center":[0.255,0.61],"feather":12,"opacity":0.85,"z":0},{"id":"right","corners":[[0.65,0.40],[0.84,0.40],[0.84,0.82],[0.65,0.82]],"center":[0.745,0.61],"feather":12,"opacity":0.85,"z":2}]}""")
    private fun point(a: JSONArray): FxPoint {
        require(a.length() == 2) { "Point needs two coordinates" }
        val x = a.getDouble(0); val y = a.getDouble(1)
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) { "Coordinates must be normalized" }
        return FxPoint(x.toFloat(), y.toFloat())
    }
    private fun points(a: JSONArray, min: Int, max: Int): List<FxPoint> {
        require(a.length() in min..max) { "Invalid polygon size" }
        val p = (0 until a.length()).map { point(a.getJSONArray(it)) }
        // Convex, clockwise, non-degenerate polygons prevent folded meshes and ambiguous clipping.
        for (i in p.indices) {
            val a0=p[i]; val b=p[(i+1)%p.size]; val c=p[(i+2)%p.size]
            require((b.x-a0.x)*(c.y-b.y)-(b.y-a0.y)*(c.x-b.x)>0.00001f) { "Use a convex clockwise polygon" }
        }
        require(abs(p.indices.sumOf { i -> val a0=p[i]; val b=p[(i+1)%p.size]; (a0.x*b.y-b.x*a0.y).toDouble() }) > .0005) { "Patch too small" }
        return p
    }
    fun patches(profile: JSONObject): List<FxPatch> {
        require(profile.toString().length <= 16000 && profile.getInt("schema_version")==1) { "Unsupported calibration schema" }
        require(profile.getInt("canvas_width")==1280 && profile.getInt("canvas_height")==720) { "Calibration must be 1280x720" }
        require(profile.getString("calibration_profile_id").matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid profile ID" }
        require(profile.getString("target_label").length in 1..80) { "Name must be 1..80 characters" }
        val a=profile.getJSONArray("patches"); require(a.length()==3) { "Sofa needs face and two hand patches" }
        val out=(0..2).map { i ->
            val p=a.getJSONObject(i); val id=p.getString("id"); require(id in ids) { "Unknown patch" }
            val c=points(p.getJSONArray("corners"),4,4)
            val center=point(p.getJSONArray("center"))
            val midX=c.sumOf { it.x.toDouble() }.toFloat()/4; val midY=c.sumOf { it.y.toDouble() }.toFloat()/4
            require(abs(center.x-midX)<=.08f && abs(center.y-midY)<=.08f) { "Center bend is limited to 8%" }
            val f=p.optDouble("feather",12.0); val o=p.optDouble("opacity",1.0); val z=p.optInt("z",i)
            require(f.isFinite() && f in 0.0..64.0 && o.isFinite() && o in 0.0..1.0 && z in 0..2) { "Invalid feather, opacity or overlap order" }
            val patch=FxPatch(id,c,center,points(p.optJSONArray("polygon_mask")?:p.getJSONArray("corners"),3,12),f.toFloat(),o.toFloat(),z)
            // Check every mesh cell, including the strongest center deformation.
            val mesh=mesh(patch)
            for(y in 0..7) for(x in 0..7) {
                val i0=(y*9+x)*2; val i1=i0+2; val i2=i0+18; val i3=i2+2
                fun cross(a:Int,b:Int,c0:Int)=(mesh[b]-mesh[a])*(mesh[c0+1]-mesh[a+1])-(mesh[b+1]-mesh[a+1])*(mesh[c0]-mesh[a])
                require(cross(i0,i1,i2)>0.01f && cross(i1,i3,i2)>0.01f) { "Center folds the patch; move it closer to the middle" }
            }
            patch
        }
        require(out.map { it.id }.toSet()==ids.toSet()) { "Duplicate patch" }
        return out.sortedBy { it.z }
    }
    fun mesh(p: FxPatch): FloatArray {
        val c=p.corners; val midX=c.sumOf { it.x.toDouble() }.toFloat()/4; val midY=c.sumOf { it.y.toDouble() }.toFloat()/4
        return FloatArray(9*9*2).also { out ->
            for(y in 0..8) for(x in 0..8) {
                val u=x/8f; val v=y/8f; val w=16*u*(1-u)*v*(1-v); val i=(y*9+x)*2
                out[i]=((1-u)*(1-v)*c[0].x+u*(1-v)*c[1].x+u*v*c[2].x+(1-u)*v*c[3].x+(p.center.x-midX)*w)*1280
                out[i+1]=((1-u)*(1-v)*c[0].y+u*(1-v)*c[1].y+u*v*c[2].y+(1-u)*v*c[3].y+(p.center.y-midY)*w)*720
            }
        }
    }
    fun validateScene(scene: JSONObject) {
        require(scene.toString().length<=2000 && scene.optString("effect")=="sofa-spirit") { "Unknown FX effect" }
        require(scene.getString("profileId").matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid FX profile" }
        validateSettings(scene)
    }
    fun validateSettings(p: JSONObject) {
        for ((key,range) in listOf("intensity" to 0.0..1.0,"speed" to .25..2.0)) {
            if(p.has(key)) { val v=p.getDouble(key); require(v.isFinite() && v in range) { "Invalid $key" } }
        }
        if(p.has("loop")) require(p.get("loop") is Boolean) { "Loop must be boolean" }
    }
}
