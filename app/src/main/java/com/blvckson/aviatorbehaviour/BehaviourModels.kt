package com.blvckson.aviatorbehaviour

data class PlaneState(val found:Boolean,val x:Float,val y:Float,val vx:Float,val vy:Float,val acceleration:Float)
data class VisualEvent(val time:Long,val type:String,val score:Double,val detail:String)

data class BehaviourSample(
    val time:Long,
    val similarity:Double,
    val visualChange:Double,
    val planeMotion:Double,
    val ultraWatch:Double,
    val red:Double,
    val stable:Double,
    val planeX:Double = 0.0,
    val planeY:Double = 0.0
)
// Build #8 baseline preserved; additive movement-path fields are used only by the new similarity calculation.
