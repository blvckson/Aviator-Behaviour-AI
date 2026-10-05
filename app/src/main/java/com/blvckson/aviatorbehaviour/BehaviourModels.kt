package com.blvckson.aviatorbehaviour
data class PlaneState(val found:Boolean,val x:Float,val y:Float,val vx:Float,val vy:Float,val acceleration:Float)
data class VisualEvent(val time:Long,val type:String,val score:Double,val detail:String)
