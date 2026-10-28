package com.hugo.projector

import org.json.JSONObject

/** One normalized, bounded media region. Independent of the saved Canvas/FX mappings. */
data class MediaPlacement(val x: Double,val y: Double,val width: Double,val height: Double) {
    fun json(): JSONObject=JSONObject().put("x",x).put("y",y).put("width",width).put("height",height)
    companion object {
        fun parse(p: JSONObject?): MediaPlacement {
            if(p==null)return MediaPlacement(0.0,0.0,1.0,1.0)
            require(p.toString().length<=512) { "Media region too large" }
            val x=p.getDouble("x");val y=p.getDouble("y");val w=p.getDouble("width");val h=p.getDouble("height")
            require(listOf(x,y,w,h).all { it.isFinite() } && x in 0.0..1.0 && y in 0.0..1.0 && w in .02..1.0 && h in .02..1.0 && x+w<=1.000001 && y+h<=1.000001) { "Keep the media region inside the scene" }
            return MediaPlacement(x,y,w,h)
        }
    }
}
